package net.programmierecke.radiodroid2.players.exoplayer;

import android.net.Uri;
import net.programmierecke.radiodroid2.utils.AppLog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;

/**
 * Radio fork: live time-shift.
 * <p>
 * A feeder thread reads the network stream (through the normal IcyDataSource, so metadata and
 * recording keep working) into a ring buffer; the player reads from the ring.
 * <p>
 * Model (all positions are absolute stream byte offsets, converted with the stream bitrate):
 * <ul>
 * <li><b>playhead</b>: the byte being heard = start of the current reader + the player's
 *   playback position, never past the reader cursor.</li>
 * <li><b>live</b>: where an uninterrupted player would be. Servers send faster than realtime
 *   (an opening burst and sometimes sustained), so the raw write head is not "live". Live is
 *   anchored when playback starts at live (first start or Go live) and then advances with wall
 *   time: live = min(write head, anchorPos + elapsed * bytesPerSecond), never behind the
 *   playhead.</li>
 * <li>offset shown = live - playhead; history shown = playhead - oldest retained byte.</li>
 * <li>A Back tap starts the next reader exactly N s before the playhead (clamped to the oldest
 *   byte); Go live starts it at live.</li>
 * <li>The writer never overwrites bytes the reader has not read yet: when read-ahead would
 *   exceed a quarter of the ring, the network read is paused (backpressure), so the rest of
 *   the ring keeps history. Only old bytes behind the reader are ever dropped.</li>
 * </ul>
 */
public class TimeShiftBuffer {
    static final String LOG = "RadioArkivSkip";
    private static final String TAG = "TimeShift";
    static final int MARGIN = 64 * 1024;

    /** Wall clock, replaceable in tests. */
    public interface Clock { long nowMs(); }

    private final RingStore ring;
    private final int capacity;
    private final long maxBehindMs;
    private final Clock clock;
    private volatile double knownBytesPerSecond;
    private long written;          // write head: total bytes ever written
    private long firstByteTime;
    private volatile boolean stopped;
    private volatile IOException error;

    private int generation;        // bumped on each shift; older readers are dead
    private long startPos = -1;    // start used by every reader open of the current prepare
    private long readerPos;        // cursor of the current reader
    private long liveAnchorPos = -1;
    private long liveAnchorTime;
    private boolean anchorPending = true; // next reader open (at live) sets the anchor

    private final DataSource.Factory upstreamFactory;
    private final Uri uri;
    private Thread feeder;

    public TimeShiftBuffer(@NonNull DataSource.Factory upstreamFactory, @NonNull Uri uri,
                           @NonNull RingStore ring, long maxBehindMs, int bitrateKbps) {
        this(upstreamFactory, uri, ring, maxBehindMs, bitrateKbps, System::currentTimeMillis);
    }

    TimeShiftBuffer(@NonNull DataSource.Factory upstreamFactory, @NonNull Uri uri,
                    @NonNull RingStore ring, long maxBehindMs, int bitrateKbps, @NonNull Clock clock) {
        this.upstreamFactory = upstreamFactory;
        this.uri = uri;
        this.ring = ring;
        this.capacity = ring.capacity();
        this.maxBehindMs = maxBehindMs;
        this.clock = clock;
        this.knownBytesPerSecond = bitrateKbps > 0 ? bitrateKbps * 125.0 : 0;
    }

    /** Bitrate seen by the decoder (more reliable than the directory's value). */
    public synchronized void setBitrateKbps(int kbps) {
        if (kbps > 0 && Math.abs(kbps * 125.0 - knownBytesPerSecond) > 1) {
            AppLog.d(LOG, "bitrate " + (knownBytesPerSecond / 125.0) + " -> " + kbps + " kbps");
            // keep live where it is when the rate changes
            if (liveAnchorPos >= 0) {
                long now = clock.nowMs();
                liveAnchorPos = rawLive(now);
                liveAnchorTime = now;
            }
            knownBytesPerSecond = kbps * 125.0;
        }
    }

    public synchronized long written() { return written; }
    public synchronized long readerPos() { return readerPos; }
    public synchronized long startPos() { return startPos; }
    public int capacity() { return capacity; }

    public void start() {
        feeder = new Thread(this::feed, "TimeShiftFeeder");
        feeder.setDaemon(true);
        feeder.start();
    }

    public void stop() {
        stopped = true;
        if (feeder != null) feeder.interrupt();
        synchronized (this) { notifyAll(); }
        ring.close();
    }

    private void feed() {
        byte[] buf = new byte[16 * 1024];
        int failures = 0;
        while (!stopped) {
            DataSource src = upstreamFactory.createDataSource();
            try {
                src.open(new DataSpec(uri));
                failures = 0;
                // Audio only into the ring: strip in-band ICY metadata, keep whole frames.
                IcyStripper icy = new IcyStripper(IcyStripper.metaIntFrom(src.getResponseHeaders()));
                framer.newConnection();
                while (!stopped) {
                    int n = src.read(buf, 0, buf.length);
                    if (n == C.RESULT_END_OF_INPUT) break;
                    if (n > 0) {
                        int audio = icy.strip(buf, 0, n);
                        if (audio > 0) framer.push(buf, audio);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                if (stopped) return;
                AppLog.w(TAG, "feeder error", e);
                if (++failures > 5) {
                    error = e;
                    synchronized (this) { notifyAll(); }
                    return;
                }
            } finally {
                try { src.close(); } catch (Exception ignored) { }
            }
            if (!stopped) {
                AppLog.d(LOG, "stream reconnect (partial frame dropped)");
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }
    }

    final Framer framer = new Framer();

    /**
     * Commits only whole MPEG/ADTS frames (each verified by the header that follows it), so the
     * ring never holds ID3 tags, partial frames from a reconnect, or junk. Streams that are not
     * MPEG audio or ADTS (e.g. Ogg) are passed through unchanged.
     */
    final class Framer {
        private static final int DETECT_LIMIT = 128 * 1024;
        private byte[] pend = new byte[64 * 1024];
        private int len;
        private boolean synced;
        private int mode; // 0 detect, 1 framed, 2 raw, 3 ogg pages
        private volatile byte[] oggHeaders;   // header pages (granule 0), replayed before every reader
        private boolean oggHeadersDone;
        private java.io.ByteArrayOutputStream oggHeaderBuf;
        private long detectSeen;
        long dropped;

        boolean framed() { return mode == 1; }
        boolean ogg() { return mode == 3; }
        /** Ogg header pages once complete (first audio page seen), else null. */
        byte[] oggHeaders() { return oggHeaders; }

        void newConnection() {
            if (len > 0 && mode != 2) dropped += len;
            len = 0;
            synced = false;
        }

        void push(byte[] b, int n) throws IOException, InterruptedException {
            if (mode == 2) { write(b, n); return; }
            if (len + n > pend.length) pend = java.util.Arrays.copyOf(pend, Math.max(pend.length * 2, len + n));
            System.arraycopy(b, 0, pend, len, n);
            len += n;
            if (mode == 0 && detectSeen == 0 && len >= 4 && OggPages.isPage(pend, 0)) {
                AppLog.d(LOG, "framer: Ogg stream, storing whole pages");
                mode = 3;
            }
            if (mode == 3) { pushOgg(); return; }
            if (mode == 0) detectSeen += n;
            int p = 0;
            while (len - p >= MpegFrames.HEADER) {
                if (synced) {
                    int fl = MpegFrames.frameLength(pend, p);
                    if (fl <= 0) { synced = false; continue; }
                    if (p + fl + MpegFrames.HEADER > len) break; // wait for the next header
                    if (MpegFrames.frameLength(pend, p + fl) > 0 && MpegFrames.sameStream(pend, p, p + fl)) {
                        write(pend, p, fl);
                        p += fl;
                    } else {
                        synced = false; // frame cut by a splice: drop it and resync
                        p += 1;
                        dropped += 1;
                    }
                } else {
                    int sync = MpegFrames.findSync(pend, p, len);
                    if (sync < 0) {
                        int keep = 8 * 1024;
                        if (len - p > keep) { dropped += len - keep - p; p = len - keep; }
                        break;
                    }
                    if (sync > p) AppLog.d(LOG, "framer: skipped " + (sync - p) + " non-audio bytes");
                    dropped += sync - p;
                    p = sync;
                    synced = true;
                    mode = 1;
                }
            }
            if (p > 0) { System.arraycopy(pend, p, pend, 0, len - p); len -= p; }
            if (mode == 0 && detectSeen > DETECT_LIMIT) {
                AppLog.d(LOG, "framer: not MPEG/ADTS, passing through");
                mode = 2;
                write(pend, 0, len);
                len = 0;
            }
        }
    }

    /**
     * Ogg (Opus/Vorbis/FLAC): only whole pages go into the ring. The header pages at the start of
     * the stream (granule 0) are kept aside and replayed in front of every reader, because the
     * decoder cannot start mid-stream without them. Header pages sent again after a reconnect
     * are dropped.
     */
    private void pushOgg() throws IOException, InterruptedException {
        Framer f = framer;
        int p = 0;
        while (f.len - p >= OggPages.MIN_HEADER) {
            if (!OggPages.isPage(f.pend, p)) {
                int next = OggPages.find(f.pend, p + 1, f.len);
                int to = next < 0 ? Math.max(p, f.len - 3) : next;
                f.dropped += to - p;
                p = to;
                if (next < 0) break;
                continue;
            }
            int pl = OggPages.pageLength(f.pend, p, f.len);
            if (pl < 0) break; // need more bytes
            if (OggPages.granule(f.pend, p) == 0) {
                if (!f.oggHeadersDone) {
                    if (f.oggHeaderBuf == null) f.oggHeaderBuf = new java.io.ByteArrayOutputStream();
                    f.oggHeaderBuf.write(f.pend, p, pl);
                } // else: headers repeated after a reconnect
            } else {
                if (!f.oggHeadersDone) {
                    f.oggHeadersDone = true;
                    f.oggHeaders = f.oggHeaderBuf != null ? f.oggHeaderBuf.toByteArray() : new byte[0];
                    AppLog.d(LOG, "ogg headers: " + f.oggHeaders.length + " bytes");
                }
                write(f.pend, p, pl);
            }
            p += pl;
        }
        if (p > 0) { System.arraycopy(f.pend, p, f.pend, 0, f.len - p); f.len -= p; }
    }

    /** Bytes the writer may add now without overwriting unread data (tests). */
    synchronized long room() { return Math.max(0, writableNow()); }

    /** Free space before unread bytes would be overwritten. */
    private long writableNow() {
        // Unread data may use at most a quarter of the ring, so the rest keeps skip-back history
        // (a fast server would otherwise fill the ring with future audio).
        return (readerPos + maxReadAhead()) - written;
    }

    long maxReadAhead() {
        return Math.min(capacity - MARGIN, Math.max(capacity / 4, 16 * 1024));
    }

    /** Write n bytes; blocks (backpressure) instead of overwriting bytes the reader hasn't read. */
    synchronized void write(byte[] b, int n) throws IOException, InterruptedException {
        write(b, 0, n);
    }

    synchronized void write(byte[] b, int off, int n) throws IOException, InterruptedException {
        n += off;
        boolean logged = false;
        while (off < n) {
            if (stopped) return;
            long room = writableNow();
            if (room <= 0) {
                if (!logged) {
                    AppLog.d(LOG, "backpressure: read-ahead full, written=" + written + " readerPos=" + readerPos);
                    logged = true;
                }
                wait(500);
                continue;
            }
            int chunk = (int) Math.min(n - off, room);
            if (written == 0) firstByteTime = clock.nowMs();
            ring.write(written, b, off, chunk);
            written += chunk;
            off += chunk;
            notifyAll();
        }
    }

    /** Stream bytes per second: declared/decoder bitrate, else measured, else 128 kbit/s. */
    public synchronized double bytesPerSecond() {
        if (knownBytesPerSecond > 0) return knownBytesPerSecond;
        long el = clock.nowMs() - firstByteTime;
        if (written > 0 && el > 20000) return Math.max(2000, written * 1000.0 / el);
        return 16000;
    }

    private long bytes(long ms) { return (long) (ms * bytesPerSecond() / 1000.0); }
    private long ms(long bytes) { return (long) (bytes * 1000.0 / bytesPerSecond()); }

    /** Oldest byte still in the ring. */
    synchronized long oldest() {
        return Math.max(0, written - capacity + MARGIN);
    }

    /** Byte being heard, given the player's playback position since the current prepare. */
    public synchronized long playhead(long playedMs) {
        if (startPos < 0) return readerPos;
        long p = startPos + bytes(Math.max(0, playedMs));
        return Math.max(oldest(), Math.min(p, Math.max(readerPos, startPos)));
    }

    private long rawLive(long now) {
        if (liveAnchorPos < 0 || anchorPending) return -1; // not anchored yet: live = playhead
        return Math.min(written, liveAnchorPos + bytes(now - liveAnchorTime));
    }

    /**
     * The player started producing sound. After a start at live (first start or Go live) this
     * anchors live to what is heard right now, so startup latency does not show as an offset.
     */
    public synchronized void onPlaybackStarted(long playedMs) {
        if (!anchorPending) return;
        liveAnchorPos = playhead(playedMs);
        liveAnchorTime = clock.nowMs();
        anchorPending = false;
        AppLog.d(LOG, "live anchored at " + liveAnchorPos + " (written=" + written + ")");
    }

    /** Where an uninterrupted player would be now (see class doc). */
    public synchronized long live(long playedMs) {
        return Math.max(rawLive(clock.nowMs()), playhead(playedMs));
    }

    /** Offset shown in the UI: ms between live and the playhead. */
    public synchronized long behindLiveMs(long playedMs) {
        return ms(live(playedMs) - playhead(playedMs));
    }

    /** Skip-back history available behind the playhead, in ms. */
    public synchronized long historyMs(long playedMs) {
        return ms(playhead(playedMs) - oldest());
    }

    /** Oldest byte the playhead may move to: retained in the ring and within the max offset. */
    private long seekStart(long live) {
        return Math.max(oldest(), live - bytes(maxBehindMs));
    }

    /** Fixed length of the media session seek bar: the configured buffer size (max offset). */
    public long barMs() { return maxBehindMs; }

    /** Audio available behind live, in ms (never more than {@link #barMs}). */
    public synchronized long availableMs(long playedMs) {
        long live = live(playedMs);
        return Math.min(maxBehindMs, ms(Math.max(0, live - seekStart(live))));
    }

    /**
     * Thumb on the fixed-length bar: the right end is live, so it is barMs - offset behind live.
     * It does not move while playing (live and playhead advance together), so speed is 0.
     */
    public synchronized long barPositionMs(long playedMs) {
        return Math.max(0, maxBehindMs - Math.min(maxBehindMs, behindLiveMs(playedMs)));
    }

    /**
     * Delta for {@link #shift} that puts the thumb at posMs on the bar, clamped to the audio
     * actually available; Long.MIN_VALUE when that is within a second of live.
     */
    public synchronized long deltaForBarPosition(long playedMs, long posMs) {
        long behind = maxBehindMs - Math.max(0, Math.min(maxBehindMs, posMs));
        behind = Math.min(behind, availableMs(playedMs));
        if (behind < 1000) return Long.MIN_VALUE;
        long d = behind - behindLiveMs(playedMs);
        return d == 0 ? 1 : d; // never 0, and Long.MIN_VALUE is reserved for live
    }

    /**
     * Set where the next reader starts. deltaMs > 0 = that much further back from the playhead,
     * Long.MIN_VALUE = go live. Returns the new offset behind live in ms.
     */
    public synchronized long shift(long playedMs, long deltaMs) {
        long now = clock.nowMs();
        long head = playhead(playedMs);
        long live = live(playedMs);
        long target;
        if (deltaMs == Long.MIN_VALUE) {
            target = live;
        } else {
            target = head - bytes(deltaMs);
            target = Math.max(target, live - bytes(maxBehindMs));
            target = Math.min(target, live);
        }
        target = Math.max(target, oldest());
        if (framer.ogg()) target = alignToPage(target);
        // the old reader is going away: its unread bytes are free again
        generation++;
        readerPos = target;
        startPos = target;
        if (anchorPending && deltaMs != Long.MIN_VALUE) { liveAnchorPos = head; liveAnchorTime = now; }
        anchorPending = deltaMs == Long.MIN_VALUE; // Go live re-anchors when sound starts
        AppLog.d(LOG, "shift delta=" + deltaMs + " ring=" + capacity + " written=" + written
                + " oldest=" + oldest() + " playedMs=" + playedMs + " playhead=" + head + " live=" + live
                + " bps=" + (long) bytesPerSecond() + " target=" + target
                + " offset " + ms(live - head) + " -> " + ms(live - target) + " ms");
        notifyAll();
        return ms(live - target);
    }

    /** Next frame boundary at or after pos (ring holds whole frames only when framed). */
    private long alignToFrame(long pos) {
        if (framer.ogg()) return alignToPage(pos);
        if (!framer.framed() || pos >= written) return Math.min(pos, written);
        int n = (int) Math.min(16 * 1024, written - pos);
        byte[] b = new byte[n];
        try {
            ring.read(pos, b, 0, n);
        } catch (IOException e) {
            return pos;
        }
        int i = MpegFrames.findSync(b, 0, n);
        if (i < 0) { // near the write head: a frame that ends exactly at the head
            for (int k = 0; k + MpegFrames.HEADER <= n; k++) {
                int fl = MpegFrames.frameLength(b, k);
                if (fl > 0 && k + fl == n) return pos + k;
            }
            return written;
        }
        return pos + i;
    }

    /** Next Ogg page start at or after pos (the ring holds whole pages only). */
    private long alignToPage(long pos) {
        pos = Math.max(pos, oldest());
        while (pos < written) {
            int n = (int) Math.min(64 * 1024, written - pos);
            byte[] b = new byte[n];
            try {
                ring.read(pos, b, 0, n);
            } catch (IOException e) {
                return pos;
            }
            int i = OggPages.find(b, 0, n);
            if (i >= 0) return pos + i;
            if (n < 4) break;
            pos += n - 3;
        }
        return written;
    }

    /** After a decoder error: restart the reader just past what is heard, on a frame boundary. */
    public synchronized long recover(long playedMs) {
        long head = playhead(playedMs);
        long target = alignToFrame(Math.min(written, Math.max(oldest(), head + bytes(250))));
        long live = live(playedMs);
        generation++;
        readerPos = target;
        startPos = target;
        AppLog.d(LOG, "recover after decoder error: playhead=" + head + " -> " + target + " written=" + written
                + " droppedBytes=" + framer.dropped);
        notifyAll();
        return ms(Math.max(0, live - target));
    }

    public DataSource.Factory readerFactory() {
        return Reader::new;
    }

    private class Reader implements DataSource {
        private long pos;
        private int gen = -1;
        private Uri openUri;
        private long requested;        // dataSpec.position (bytes of the virtual stream)
        private boolean resolved;
        private byte[] prefix;         // Ogg header pages served before the ring data
        private int prefixPos;

        @Override
        public void addTransferListener(@NonNull TransferListener transferListener) { }

        @Override
        public long open(@NonNull DataSpec dataSpec) throws IOException {
            openUri = dataSpec.uri;
            synchronized (TimeShiftBuffer.this) {
                if (startPos < 0) {
                    startPos = written; // first start: live
                }
                gen = generation;
                requested = Math.max(0, dataSpec.position);
                resolved = false;
                prefix = null;
                prefixPos = 0;
                pos = Math.max(oldest(), startPos + requested);
                readerPos = pos;
                AppLog.d(LOG, "reader open: startPos=" + startPos + " written=" + written + " pos=" + pos
                        + " oldest=" + oldest() + " requested=" + requested);
                TimeShiftBuffer.this.notifyAll();
            }
            return C.LENGTH_UNSET;
        }

        /**
         * On the first read, once the stream type is known: an Ogg reader sees the header pages
         * first and then the ring from a page boundary (the virtual stream is headers + ring).
         */
        private void resolve() {
            resolved = true;
            if (!framer.ogg()) return;
            byte[] h = framer.oggHeaders();
            if (h == null) h = new byte[0];
            prefix = h;
            if (requested < h.length) {
                prefixPos = (int) requested;
                pos = alignToPage(Math.max(oldest(), startPos));
            } else {
                prefixPos = h.length;
                long p = Math.max(oldest(), startPos + requested - h.length);
                pos = requested == h.length ? alignToPage(p) : p;
            }
            readerPos = pos;
        }

        @Override
        public int read(@NonNull byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            synchronized (TimeShiftBuffer.this) {
                long deadline = System.currentTimeMillis() + 20000;
                while (true) {
                    if (gen != generation) return C.RESULT_END_OF_INPUT; // replaced by a newer reader
                    if (error != null) throw error;
                    if (stopped) return C.RESULT_END_OF_INPUT;
                    // the stream type (and Ogg headers) are known once anything was written
                    if (!resolved && written > 0) resolve();
                    if (resolved && prefix != null && prefixPos < prefix.length) {
                        int n = Math.min(length, prefix.length - prefixPos);
                        System.arraycopy(prefix, prefixPos, target, offset, n);
                        prefixPos += n;
                        return n;
                    }
                    if (resolved && pos < oldest()) {
                        pos = framer.ogg() ? alignToPage(oldest()) : oldest(); // only after a seek to very old data
                    }
                    if (resolved && written > pos) {
                        int avail = (int) Math.min(length, written - pos);
                        ring.read(pos, target, offset, avail);
                        pos += avail;
                        readerPos = pos;
                        TimeShiftBuffer.this.notifyAll(); // writer may be waiting for room
                        return avail;
                    }
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) throw new IOException("time-shift: no data from stream");
                    try {
                        TimeShiftBuffer.this.wait(Math.min(wait, 500));
                    } catch (InterruptedException e) {
                        throw new java.io.InterruptedIOException();
                    }
                }
            }
        }

        @Nullable
        @Override
        public Uri getUri() { return openUri; }

        @Override
        public void close() { }
    }
}
