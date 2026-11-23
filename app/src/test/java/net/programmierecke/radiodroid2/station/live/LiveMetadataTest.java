package net.programmierecke.radiodroid2.station.live;

import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.flac.VorbisComment;
import com.google.android.exoplayer2.metadata.icy.IcyInfo;
import com.google.android.exoplayer2.metadata.id3.CommentFrame;
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LiveMetadataTest {

    private static IcyInfo icy(String title) {
        String raw = "StreamTitle='" + title + "';";
        return new IcyInfo(raw.getBytes(StandardCharsets.ISO_8859_1), title, null);
    }


    @Test
    void readsIcyTitle() {
        StreamLiveInfo info = LiveMetadata.from(new Metadata(
                icy("Song A")));
        assertEquals("Song A", info.getTitle());
    }

    @Test
    void readsVorbisTitleAndArtist() {
        StreamLiveInfo info = LiveMetadata.from(new Metadata(
                new VorbisComment("ARTIST", "Onda Rossa"),
                new VorbisComment("TITLE", "In diretta")));
        assertEquals("Onda Rossa - In diretta", info.getTitle());
    }

    @Test
    void fallsBackToVorbisCommentWhenTitleMissing() {
        StreamLiveInfo info = LiveMetadata.from(new Metadata(
                new VorbisComment("COMMENT", "In diretta")));
        assertEquals("In diretta", info.getTitle());
    }

    @Test
    void readsId3TitleAndArtist() {
        StreamLiveInfo info = LiveMetadata.from(new Metadata(
                new TextInformationFrame("TPE1", null, "Announcer"),
                new TextInformationFrame("TIT2", null, "Hourly news")));
        assertEquals("Announcer - Hourly news", info.getTitle());
    }

    @Test
    void readsId3CommentWhenNoTitle() {
        StreamLiveInfo info = LiveMetadata.from(new Metadata(
                new CommentFrame("eng", "", "Studio break")));
        assertEquals("Studio break", info.getTitle());
    }

    @Test
    void prefersIcyTitleOverVorbisAndId3() {
        StreamLiveInfo info = LiveMetadata.from(new Metadata(
                icy("ICY title"),
                new VorbisComment("TITLE", "Vorbis title"),
                new TextInformationFrame("TIT2", null, "ID3 title")));
        assertEquals("ICY title", info.getTitle());
    }

    @Test
    void returnsNullWhenMetadataHasNoUsefulTitle() {
        assertNull(LiveMetadata.from(new Metadata(
                new VorbisComment("ALBUM", "Unused"),
                new TextInformationFrame("TALB", null, "Also unused"))));
        assertNull(LiveMetadata.from(null));
        assertNull(LiveMetadata.from(new Metadata()));
    }

    @Test
    void mergeKeepsCurrentWhenIncomingTitleIsEmpty() {
        StreamLiveInfo current = LiveMetadata.from(new Metadata(
                icy("Good")));
        StreamLiveInfo empty = LiveMetadata.merge(current, LiveMetadata.from(new Metadata(
                new VorbisComment("TITLE", "   "))));
        assertSame(current, empty);
        assertSame(current, LiveMetadata.merge(current, null));
    }

    @Test
    void mergeDoesNotReplaceIdenticalTitle() {
        StreamLiveInfo current = LiveMetadata.from(new Metadata(
                icy("Same")));
        StreamLiveInfo incoming = LiveMetadata.from(new Metadata(
                new VorbisComment("TITLE", "Same")));
        assertSame(current, LiveMetadata.merge(current, incoming));
    }

    @Test
    void mergeAcceptsANewTitle() {
        StreamLiveInfo current = LiveMetadata.from(new Metadata(
                icy("Old")));
        StreamLiveInfo incoming = LiveMetadata.from(new Metadata(
                new VorbisComment("TITLE", "New")));
        assertEquals("New", LiveMetadata.merge(current, incoming).getTitle());
    }
}
