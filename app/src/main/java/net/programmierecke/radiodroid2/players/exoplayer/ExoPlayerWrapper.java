package net.programmierecke.radiodroid2.players.exoplayer;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import net.programmierecke.radiodroid2.utils.AppLog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.icy.IcyHeaders;
import com.google.android.exoplayer2.metadata.icy.IcyInfo;
import com.google.android.exoplayer2.metadata.id3.Id3Frame;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import net.programmierecke.radiodroid2.BuildConfig;
import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;
import net.programmierecke.radiodroid2.players.PlayState;
import net.programmierecke.radiodroid2.players.PlayerWrapper;
import net.programmierecke.radiodroid2.recording.RecordableListener;
import net.programmierecke.radiodroid2.station.live.ShoutcastInfo;
import net.programmierecke.radiodroid2.station.live.LiveMetadata;
import net.programmierecke.radiodroid2.station.live.StreamLiveInfo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public class ExoPlayerWrapper implements PlayerWrapper, IcyDataSource.IcyDataSourceListener, Player.Listener {

    final private String TAG = "ExoPlayerWrapper";

    private ExoPlayer player;
    private PlayListener stateListener;

    private String streamUrl;

    private final DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

    private RecordableListener recordableListener;

    private long totalTransferredBytes;
    private long currentPlaybackTransferredBytes;

    private boolean isHls;
    private boolean isPlayingFlag;
    private StreamLiveInfo currentLiveInfo;

    private Handler playerThreadHandler;

    private Context context;
    private MediaSource audioSource;

    private Runnable fullStopTask;

    private final Object streamInfoLock = new Object();
    private ShoutcastInfo streamAudioInfo;

    private final BroadcastReceiver networkChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!localFile && fullStopTask != null && player != null && audioSource != null && Utils.hasAnyConnection(context)) {
                AppLog.i(TAG, "Regained connection. Resuming playback.");

                cancelStopTask();

                player.setMediaSource(audioSource);
                player.prepare();
                player.setPlayWhenReady(true);
            }
        }
    };

    @Override
    public void playRemote(@NonNull OkHttpClient httpClient, @NonNull String streamUrl, @NonNull Context context, boolean isAlarm) {
        // I don't know why, but it is still possible that streamUrl is null,
        // I still get exceptions from this from google
        if (!streamUrl.equals(this.streamUrl)) {
            currentPlaybackTransferredBytes = 0;
        }

        this.context = context;
        this.streamUrl = streamUrl;
        isHls = Utils.urlIndicatesHlsStream(streamUrl);
        // Drop the previous station's title so merge() cannot keep it.
        currentLiveInfo = null;

        cancelStopTask();

        stateListener.onStateChanged(PlayState.PrePlaying);

        if (player != null) {
            player.stop();
        }

        synchronized (streamInfoLock) {
            streamAudioInfo = null;
        }
        stateListener.onDataSourceShoutcastInfo(null, isHls);

        if (player == null) {
            player = new ExoPlayer.Builder(context).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(isAlarm ? C.USAGE_ALARM : C.USAGE_MEDIA).build(), false);

            player.addListener(this);
            player.addAnalyticsListener(new AnalyticEventListener());
        }

        if (playerThreadHandler == null) {
            playerThreadHandler = new Handler(Looper.getMainLooper());
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        final int retryTimeout = prefs.getInt("settings_retry_timeout", 10);
        final int retryDelay = prefs.getInt("settings_retry_delay", 100);

        DataSource.Factory dataSourceFactory = new RadioDataSourceFactory(httpClient, bandwidthMeter, this, retryTimeout, retryDelay);
        // Produces Extractor instances for parsing the media data.
        stopTimeShift();
        localFile = net.programmierecke.radiodroid2.station.DataRadioStation.isLocalFileUrl(streamUrl);
        if (localFile) {
            // Radio fork: local file (recording or file opened with Radio) - normal seekable playback
            isHls = false;
            if (!streamUrl.equals(localResumeUrl)) {
                localResumeMs = 0;
            }
            localResumeUrl = streamUrl;
            audioSource = new ProgressiveMediaSource.Factory(
                    new com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(context))
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));
            player.setMediaSource(audioSource);
            player.prepare();
            if (localResumeMs > 0) {
                player.seekTo(localResumeMs);
            }
        } else if (!isHls) {
            // Radio fork: route progressive streams through the time-shift ring buffer
            timeShift = createTimeShift(dataSourceFactory, Uri.parse(streamUrl), prefs);
            timeShift.start();
            shiftMs = 0;
            shifting = false;
            audioSource = new ProgressiveMediaSource.Factory(timeShift.readerFactory())
                    .setLoadErrorHandlingPolicy(new CustomLoadErrorHandlingPolicy())
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));
            player.setMediaSource(audioSource);
            player.prepare();
        } else {
            audioSource = new HlsMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(new CustomLoadErrorHandlingPolicy())
                    .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)));
            player.setMediaSource(audioSource);
            player.prepare();
        }

        player.setPlayWhenReady(true);

        context.registerReceiver(networkChangedReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        // State changed will be called when audio session id is available.
    }

    private TimeShiftBuffer timeShift;
    private long shiftMs;
    private int expectedBitrateKbps;

    /** Station's declared bitrate, used to size the skip-back buffer in minutes. */
    public void setExpectedBitrateKbps(int kbps) {
        expectedBitrateKbps = kbps;
    }

    private TimeShiftBuffer createTimeShift(DataSource.Factory upstream, Uri uri, SharedPreferences prefs) {
        String pref = prefs.getString(TimeShiftSettings.PREF_KEY, TimeShiftSettings.DEFAULT);
        int kbps = expectedBitrateKbps;
        int capacity = TimeShiftSettings.capacityFor(pref, kbps);
        long maxBehind = TimeShiftSettings.maxBehindMsFor(pref, kbps);
        RingStore store = null;
        if (TimeShiftSettings.useDisk(capacity)) {
            try {
                store = new RingStore.Disk(new java.io.File(context.getCacheDir(), "timeshift.buf"), capacity);
            } catch (java.io.IOException e) {
                AppLog.w(TAG, "disk time-shift buffer unavailable, using RAM", e);
            }
        }
        if (store == null) {
            int ramCap = Math.min(capacity, TimeShiftSettings.MAX_RAM_BYTES);
            store = new RingStore.Memory(ramCap);
            maxBehind = Math.min(maxBehind, (ramCap - 256 * 1024) * 8000L / (TimeShiftSettings.effectiveKbps(kbps) * 1000L));
        }
        return new TimeShiftBuffer(upstream, uri, store, maxBehind, kbps);
    }

    // Radio fork: local file playback state
    private boolean localFile;
    private String localResumeUrl;
    private long localResumeMs;

    public boolean isLocalFile() {
        return localFile;
    }

    public long getPositionMs() {
        return player != null ? Math.max(0, player.getCurrentPosition()) : (localFile ? localResumeMs : 0);
    }

    public long getDurationMs() {
        if (player == null) return -1;
        long d = player.getDuration();
        return d == C.TIME_UNSET ? -1 : d;
    }

    /** Seek within a local file. Must run on player thread. */
    public void seekTo(long positionMs) {
        if (!localFile) return;
        positionMs = Math.max(0, positionMs);
        long d = getDurationMs();
        if (d > 0) positionMs = Math.min(positionMs, d);
        if (player != null) {
            player.seekTo(positionMs);
        } else {
            localResumeMs = positionMs;
        }
    }

    private void stopTimeShift() {
        if (timeShift != null) {
            timeShift.stop();
            timeShift = null;
        }
        shiftMs = 0;
    }

    public boolean canTimeShift() {
        return timeShift != null && player != null;
    }

    /** Move playback deltaMs further behind live (negative = towards live). Must run on player thread. */
    public void shiftBy(long deltaMs) {
        if (localFile) {
            // "Back 5s" on a local file is a plain relative seek; "Go live" means nothing here.
            if (deltaMs != Long.MIN_VALUE) seekTo(getPositionMs() - deltaMs);
            return;
        }
        if (!canTimeShift()) return;
        // Measure from what is actually heard: reader cursor behind live plus the player's own
        // read-ahead. Using only the last requested shift made Back 5s land near the live edge.
        long played = Math.max(0, player.getCurrentPosition());
        shiftMs = timeShift.shift(played, deltaMs);
        AppLog.d(TimeShiftBuffer.LOG, "tap delta=" + deltaMs + " playerPositionMs=" + played
                + " playerBufferedMs=" + (player.getBufferedPosition() - player.getCurrentPosition())
                + " readerPos=" + timeShift.readerPos() + " written=" + timeShift.written()
                + " newOffsetMs=" + shiftMs);
        // A live progressive stream has no seek map, so ExoPlayer cannot seek inside it: the
        // source is re-prepared and the time-shift reader starts at the new position. Keep the
        // app in "Playing" meanwhile (no state flicker, elapsed time not reset).
        shifting = true;
        player.stop();
        player.setMediaSource(audioSource);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private boolean shifting;
    private final java.util.ArrayDeque<Long> recoveries = new java.util.ArrayDeque<>();

    /**
     * A decoder/parser error on a time-shifted live stream: reopen the ring just past what was
     * heard (on a frame boundary) instead of stopping. At most 5 times per minute.
     */
    private boolean tryRecoverTimeShift(PlaybackException error) {
        if (!canTimeShift() || localFile) return false;
        int code = error.errorCode;
        boolean decodeOrParse = (code >= 3000 && code < 5000);
        if (!decodeOrParse) return false;
        long now = System.currentTimeMillis();
        while (!recoveries.isEmpty() && now - recoveries.peekFirst() > 60_000) recoveries.removeFirst();
        if (recoveries.size() >= 5) {
            AppLog.d(TimeShiftBuffer.LOG, "too many decoder errors, giving up: " + error.getErrorCodeName());
            return false;
        }
        recoveries.addLast(now);
        AppLog.d(TimeShiftBuffer.LOG, "decoder/parser error " + error.getErrorCodeName() + ", recovering", error);
        final long played = Math.max(0, player.getCurrentPosition());
        playerThreadHandler.post(() -> {
            if (!canTimeShift()) return;
            shiftMs = timeShift.recover(played);
            shifting = true;
            player.stop();
            player.setMediaSource(audioSource);
            player.prepare();
            player.setPlayWhenReady(true);
        });
        return true;
    }

    /** Fixed seek bar length for the media session (buffer size), or -1 when not available. */
    public long getTimeShiftBarMs() {
        if (!canTimeShift() || localFile) return -1;
        return timeShift.barMs();
    }

    /** Thumb on that bar: bar length minus the offset behind live (right end = live). */
    public long getTimeShiftPositionMs() {
        if (!canTimeShift() || localFile) return -1;
        long bar = timeShift.barMs();
        return Math.max(0, bar - Math.min(bar, getBehindLiveMs()));
    }

    /** Move the thumb to positionMs on the bar (clamped to available audio); the end means live. Player thread. */
    public void seekInTimeShift(long positionMs) {
        if (!canTimeShift() || localFile) return;
        long delta = timeShift.deltaForBarPosition(Math.max(0, player.getCurrentPosition()), positionMs);
        if (delta == Long.MIN_VALUE && getBehindLiveMs() == 0) return; // already live
        shiftBy(delta);
    }

    public long getBehindLiveMs() {
        if (!canTimeShift()) return 0;
        // While re-preparing the player position restarts at 0; show the offset just set.
        if (shifting) return shiftMs;
        long behind = timeShift.behindLiveMs(Math.max(0, player.getCurrentPosition()));
        return behind < 1000 ? 0 : behind;
    }

    @Override
    public void pause() {
        AppLog.i(TAG, "Pause. Stopping exoplayer.");
        stopTimeShift();
        if (localFile && player != null) {
            localResumeMs = Math.max(0, player.getCurrentPosition());
        }

        cancelStopTask();

        if (player != null) {
            context.unregisterReceiver(networkChangedReceiver);
            player.stop();
            player.release();
            player = null;
        }
    }

    @Override
    public void stop() {
        AppLog.i(TAG, "Stopping exoplayer.");
        stopTimeShift();
        currentLiveInfo = null;
        localResumeMs = 0;
        localResumeUrl = null;

        cancelStopTask();

        if (player != null) {
            context.unregisterReceiver(networkChangedReceiver);
            player.stop();
            player.release();
            player = null;
        }

        stopRecording();
    }

    @Override
    public boolean isPlaying() {
        return player != null && isPlayingFlag;
    }

    @Override
    public long getBufferedMs() {
        if (canTimeShift()) {
            // Skip-back history behind what is heard (same model as the offset and the taps)
            return timeShift.historyMs(Math.max(0, player.getCurrentPosition()));
        }
        if (player != null) {
            return (int) (player.getBufferedPosition() - player.getCurrentPosition());
        }

        return 0;
    }

    @Override
    public int getAudioSessionId() {
        if (player != null) {
            return player.getAudioSessionId();
        }
        return 0;
    }

    @Override
    public long getTotalTransferredBytes() {
        return totalTransferredBytes;
    }

    @Override
    public long getCurrentPlaybackTransferredBytes() {
        return currentPlaybackTransferredBytes;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public void setVolume(float newVolume) {
        if (player != null) {
            player.setVolume(newVolume);
        }
    }

    @Override
    public void setStateListener(PlayListener listener) {
        stateListener = listener;
    }

    @Override
    public void onDataSourceConnected() {

    }

    @Override
    public void onDataSourceConnectionLost() {

    }

    @Override
    public void onMetadata(@NonNull Metadata metadata) {
        if (BuildConfig.DEBUG) AppLog.d(TAG, "META: " + metadata);
        final int length = metadata.length();
        for (int i = 0; i < length; i++) {
            final Metadata.Entry entry = metadata.get(i);
            if (entry instanceof IcyHeaders) {
                final IcyHeaders icyHeaders = (IcyHeaders) entry;
                AppLog.d(TAG, "IcyHeaders: " + icyHeaders);
                onDataSourceShoutcastInfo(new ShoutcastInfo(icyHeaders));
            }
        }
        StreamLiveInfo merged = LiveMetadata.merge(currentLiveInfo, LiveMetadata.from(metadata));
        if (merged != null && merged != currentLiveInfo) {
            currentLiveInfo = merged;
            onDataSourceStreamLiveInfo(merged);
        }
    }

    @Override
    public void onDataSourceConnectionLostIrrecoverably() {
        AppLog.i(TAG, "Connection lost irrecoverably.");
    }

    void resumeWhenNetworkConnected() {
        playerThreadHandler.post(() -> {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            int resumeWithin = sharedPref.getInt("settings_resume_within", 60);
            if (resumeWithin > 0) {
                AppLog.d(TAG, "Trying to resume playback within " + resumeWithin + "s.");

                // We want user to be able to paused during connection loss.
                // TODO: Find a way to notify user that even if current state is Playing
                //       we are actually trying to reconnect.
                //stateListener.onStateChanged(PlayState.Paused);

                cancelStopTask();

                fullStopTask = () -> {
                    stop();
                    stateListener.onPlayerError(R.string.giving_up_resume);

                    ExoPlayerWrapper.this.fullStopTask = null;
                };
                playerThreadHandler.postDelayed(fullStopTask, resumeWithin * 1000L);

                stateListener.onPlayerWarning(R.string.warning_no_network_trying_resume);
            } else {
                stop();

                stateListener.onPlayerError(R.string.error_stream_reconnect_timeout);
            }
        });
    }

    @Override
    public void onDataSourceShoutcastInfo(@Nullable ShoutcastInfo shoutcastInfo) {
        ShoutcastInfo publishedInfo;
        synchronized (streamInfoLock) {
            if (shoutcastInfo != null) {
                if (streamAudioInfo == null) {
                    streamAudioInfo = new ShoutcastInfo();
                }
                streamAudioInfo.mergeFrom(shoutcastInfo);
            }
            publishedInfo = streamAudioInfo != null && streamAudioInfo.hasUsefulInfo()
                    ? new ShoutcastInfo(streamAudioInfo)
                    : null;
        }
        stateListener.onDataSourceShoutcastInfo(publishedInfo, isHls);
    }

    private void onObservedAudioFormat(@NonNull Format format) {
        int bitrateBitsPerSecond = format.averageBitrate > 0
                ? format.averageBitrate
                : format.bitrate;
        // The decoder's bitrate is more reliable than the station directory's value
        if (timeShift != null && bitrateBitsPerSecond > 0) timeShift.setBitrateKbps(bitrateBitsPerSecond / 1000);

        ShoutcastInfo publishedInfo;
        synchronized (streamInfoLock) {
            if (streamAudioInfo == null) {
                streamAudioInfo = new ShoutcastInfo();
            }
            streamAudioInfo.mergeObservedAudioFormat(
                    format.sampleMimeType,
                    format.codecs,
                    format.sampleRate,
                    format.channelCount,
                    bitrateBitsPerSecond);
            publishedInfo = new ShoutcastInfo(streamAudioInfo);
        }

        if (BuildConfig.DEBUG) {
            AppLog.d(TAG, "Source audio format: mime=" + format.sampleMimeType
                    + ", codecs=" + format.codecs
                    + ", sampleRate=" + format.sampleRate
                    + ", channels=" + format.channelCount
                    + ", averageBitrate=" + format.averageBitrate);
        }
        stateListener.onDataSourceShoutcastInfo(publishedInfo, isHls);
    }

    @Override
    public void onDataSourceStreamLiveInfo(StreamLiveInfo streamLiveInfo) {
        stateListener.onDataSourceStreamLiveInfo(streamLiveInfo);
    }

    @Override
    public void onDataSourceBytesRead(byte[] buffer, int offset, int length) {
        totalTransferredBytes += length;
        currentPlaybackTransferredBytes += length;

        if (recordableListener != null) {
            recordableListener.onBytesAvailable(buffer, offset, length);
        }
    }

    @Override
    public boolean canRecord() {
        return player != null && !localFile;
    }

    @Override
    public void startRecording(@NonNull RecordableListener recordableListener) {
        this.recordableListener = recordableListener;
    }

    @Override
    public void stopRecording() {
        if (recordableListener != null) {
            recordableListener.onRecordingEnded();
            recordableListener = null;
        }
    }

    @Override
    public boolean isRecording() {
        return recordableListener != null;
    }

    @Override
    public Map<String, String> getRecordNameFormattingArgs() {
        return null;
    }

    @Override
    public String getExtension() {
        return isHls ? "ts" : "mp3";
    }

    private void cancelStopTask() {
        if (fullStopTask != null) {
            playerThreadHandler.removeCallbacks(fullStopTask);
            fullStopTask = null;
        }
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        // Do nothing
    }

    @Override
    public void onPlayerErrorChanged(PlaybackException error) {
        AppLog.d(TAG, "Player error: ", error);
        if (error != null && tryRecoverTimeShift(error)) return;
        // Stop playing since it is either irrecoverable error in the player or our data source failed to reconnect.
        if (fullStopTask != null) {
            stop();
            stateListener.onPlayerError(R.string.error_play_stream);
        }
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
        // Do nothing
    }

    final class CustomLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
        final int MIN_RETRY_DELAY_MS = 10;
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        // We need to read the retry delay here on each error again because the user might change
        // this value between retries and experiment with different vales to get the best result for
        // the specific situation. We also need to make sure that a sensible minimum value is chosen.
        int getSanitizedRetryDelaySettingsMs() {
            return Math.max(sharedPrefs.getInt("settings_retry_delay", 100), MIN_RETRY_DELAY_MS);
        }

        @Override
        public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {

            int retryDelay = getSanitizedRetryDelaySettingsMs();
            IOException exception = loadErrorInfo.exception;

            if (timeShift != null && exception instanceof com.google.android.exoplayer2.ParserException) {
                // The time-shift ring is local: retrying the same bytes every few ms can never
                // succeed (this looped forever in BUFFERING). Surface it so the decoder-error
                // recovery reopens on a clean boundary, or playback stops after 5 tries.
                AppLog.d(TimeShiftBuffer.LOG, "parser error on time-shift data, not retrying: " + exception);
                return C.TIME_UNSET;
            }
            if (exception instanceof HttpDataSource.InvalidContentTypeException) {
                stateListener.onPlayerError(R.string.error_play_stream);
                return C.TIME_UNSET; // Immediately surface error if we cannot play content type
            }

            if (!Utils.hasAnyConnection(context)) {
                int resumeWithinS = sharedPrefs.getInt("settings_resume_within", 60);
                if (resumeWithinS > 0) {
                    resumeWhenNetworkConnected();
                    retryDelay = 1000 * resumeWithinS + retryDelay;
                }
            }

            if (BuildConfig.DEBUG) {
                AppLog.d(TAG, "Providing retry delay of " + retryDelay + "ms " +
                        "error count: " + loadErrorInfo.errorCount + ", " +
                        "exception " + exception.getClass() + ", " +
                        "message: " + exception.getMessage());
            }
            return retryDelay;
        }

        @Override
        public int getMinimumLoadableRetryCount(int dataType) {
            return sharedPrefs.getInt("settings_retry_timeout", 10) * 1000 / getSanitizedRetryDelaySettingsMs() + 1;
        }
    }

    private class AnalyticEventListener implements AnalyticsListener {
        @Override
        public void onAudioInputFormatChanged(@NonNull EventTime eventTime, @NonNull Format format,
                                              @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
            onObservedAudioFormat(format);
        }

        @Override
        public void onPlayerStateChanged(EventTime eventTime, boolean playWhenReady, int playbackState) {
            isPlayingFlag = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING;
            if (playbackState == Player.STATE_READY && timeShift != null && player != null) {
                timeShift.onPlaybackStarted(Math.max(0, player.getCurrentPosition()));
            }
            if (shifting) {
                if (playbackState == Player.STATE_READY) {
                    shifting = false;
                    cancelStopTask();
                    AppLog.d(TimeShiftBuffer.LOG, "re-prepared after shift, behind=" + shiftMs + " ms");
                }
                if (playbackState != Player.STATE_ENDED) return;
                shifting = false;
            }

            switch (playbackState) {
                case Player.STATE_READY:
                    cancelStopTask();
                    stateListener.onStateChanged(PlayState.Playing);
                    break;
                case Player.STATE_BUFFERING:
                    stateListener.onStateChanged(PlayState.PrePlaying);
                    break;
            }

        }

        @Override
        public void onTimelineChanged(@NonNull EventTime eventTime, int reason) {

        }

        @Override
        public void onPlaybackParametersChanged(@NonNull EventTime eventTime, @NonNull PlaybackParameters playbackParameters) {

        }

        @Override
        public void onRepeatModeChanged(@NonNull EventTime eventTime, int repeatMode) {

        }

        @Override
        public void onShuffleModeChanged(@NonNull EventTime eventTime, boolean shuffleModeEnabled) {

        }

        @Override
        public void onBandwidthEstimate(@NonNull EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {

        }

        @Override
        public void onSurfaceSizeChanged(@NonNull EventTime eventTime, int width, int height) {

        }

        @Override
        public void onMetadata(@NonNull EventTime eventTime, @NonNull Metadata metadata) {

        }

        @Override
        public void onAudioAttributesChanged(@NonNull EventTime eventTime, @NonNull AudioAttributes audioAttributes) {

        }

        @Override
        public void onVolumeChanged(@NonNull EventTime eventTime, float volume) {

        }

        @Override
        public void onDroppedVideoFrames(@NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {

        }

        @Override
        public void onDrmKeysLoaded(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmSessionManagerError(@NonNull EventTime eventTime, @NonNull Exception error) {

        }

        @Override
        public void onDrmKeysRestored(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmKeysRemoved(@NonNull EventTime eventTime) {

        }

        @Override
        public void onDrmSessionReleased(@NonNull EventTime eventTime) {

        }
    }
}
