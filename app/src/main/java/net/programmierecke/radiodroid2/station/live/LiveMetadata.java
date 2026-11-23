package net.programmierecke.radiodroid2.station.live;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.flac.VorbisComment;
import com.google.android.exoplayer2.metadata.icy.IcyInfo;
import com.google.android.exoplayer2.metadata.id3.CommentFrame;
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns ExoPlayer metadata (ICY, Vorbis/Ogg comments, and ID3, including HLS ID3)
 * into a {@link StreamLiveInfo} title, without replacing a good title with an empty one.
 */
public final class LiveMetadata {
    private LiveMetadata() {
    }

    @Nullable
    public static StreamLiveInfo from(@Nullable Metadata metadata) {
        if (metadata == null) {
            return null;
        }

        Map<String, String> raw = new LinkedHashMap<>();
        String icyTitle = null;
        String vorbisTitle = null;
        String vorbisArtist = null;
        String vorbisComment = null;
        String id3Title = null;
        String id3Artist = null;
        String id3Comment = null;

        for (int i = 0; i < metadata.length(); i++) {
            Metadata.Entry entry = metadata.get(i);
            if (entry == null) {
                continue;
            }
            if (entry instanceof IcyInfo) {
                IcyInfo icy = (IcyInfo) entry;
                if (!isBlank(icy.title)) {
                    icyTitle = icy.title.trim();
                    raw.put("StreamTitle", icyTitle);
                }
                if (!isBlank(icy.url)) {
                    raw.put("StreamUrl", icy.url.trim());
                }
            } else if (entry instanceof VorbisComment) {
                VorbisComment comment = (VorbisComment) entry;
                if (isBlank(comment.key) || comment.value == null) {
                    continue;
                }
                String key = comment.key.toUpperCase(Locale.ROOT);
                String value = comment.value.trim();
                if (value.isEmpty()) {
                    continue;
                }
                switch (key) {
                    case "TITLE":
                        vorbisTitle = value;
                        raw.put("TITLE", value);
                        break;
                    case "ARTIST":
                        vorbisArtist = value;
                        raw.put("ARTIST", value);
                        break;
                    case "COMMENT":
                    case "DESCRIPTION":
                        vorbisComment = value;
                        raw.put("COMMENT", value);
                        break;
                    default:
                        break;
                }
            } else if (entry instanceof TextInformationFrame) {
                TextInformationFrame frame = (TextInformationFrame) entry;
                String value = textFrameValue(frame);
                if (isBlank(value)) {
                    continue;
                }
                value = value.trim();
                if ("TIT2".equals(frame.id) || "TT2".equals(frame.id)) {
                    id3Title = value;
                } else if ("TPE1".equals(frame.id) || "TP1".equals(frame.id)) {
                    id3Artist = value;
                } else if ("TIT3".equals(frame.id)) {
                    if (id3Comment == null) {
                        id3Comment = value;
                    }
                }
            } else if (entry instanceof CommentFrame) {
                CommentFrame frame = (CommentFrame) entry;
                if (!isBlank(frame.text)) {
                    id3Comment = frame.text.trim();
                }
            }
        }

        String title = firstNonBlank(
                icyTitle,
                joinArtistTrack(vorbisArtist, vorbisTitle),
                vorbisTitle,
                joinArtistTrack(id3Artist, id3Title),
                id3Title,
                vorbisComment,
                id3Comment);
        if (title == null) {
            return null;
        }
        raw.put("StreamTitle", title);
        return new StreamLiveInfo(raw);
    }

    /**
     * Prefer a non-empty incoming title. Keep the current title if the update is empty
     * or identical, so empty Vorbis/ID3 frames cannot wipe a good ICY title or cause flicker.
     */
    @Nullable
    public static StreamLiveInfo merge(@Nullable StreamLiveInfo current, @Nullable StreamLiveInfo incoming) {
        if (incoming == null || isBlank(incoming.getTitle())) {
            return current;
        }
        if (current != null && incoming.getTitle().equals(current.getTitle())) {
            return current;
        }
        return incoming;
    }

    @Nullable
    static String joinArtistTrack(@Nullable String artist, @Nullable String track) {
        boolean hasArtist = !isBlank(artist);
        boolean hasTrack = !isBlank(track);
        if (hasArtist && hasTrack) {
            return artist.trim() + " - " + track.trim();
        }
        return null;
    }

    @Nullable
    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @Nullable
    private static String textFrameValue(TextInformationFrame frame) {
        if (!isBlank(frame.value)) {
            return frame.value;
        }
        try {
            Object values = TextInformationFrame.class.getField("values").get(frame);
            if (values instanceof List && !((List<?>) values).isEmpty()) {
                Object first = ((List<?>) values).get(0);
                return first != null ? first.toString() : null;
            }
        } catch (ReflectiveOperationException ignored) {
            // Older ExoPlayer only has the deprecated value field.
        }
        return null;
    }
}
