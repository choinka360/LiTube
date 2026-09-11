package com.hhst.youtubelite.extractor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.Gson;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class YoutubeExtractorRefreshTest {
    @Test
    public void refreshBypassesCachedStreamDetailsAndFetchesAgain() throws Exception {
        InfoCache cache = mock(InfoCache.class);
        Fetch play = mock(Fetch.class);
        Fetch info = mock(Fetch.class);
        AuthContextFactory auth = mock(AuthContextFactory.class);
        when(auth.create(anyString())).thenReturn(new ExtractionSession().getAuth());
        when(cache.getPlaybackDetails(anyString())).thenReturn(mock(PlaybackDetails.class));
        when(cache.getVideoDetails(anyString())).thenReturn(mock(VideoDetails.class));
        when(play.fetch(anyString(), any())).thenThrow(new IOException("Network unavailable"));
        when(info.fetch(anyString(), any())).thenThrow(new IOException("Network unavailable"));
        List<Runnable> work = new ArrayList<>();
        YoutubeExtractor extractor = new YoutubeExtractor(play, info, cache, work::add, new Gson(), auth);

        CompletableFuture<PlaybackDetails> result = extractor.refreshInfo(
                "https://www.youtube.com/watch?v=abcdefghijk", new ExtractionSession());
        work.get(0).run();

        verify(cache, never()).getPlaybackDetails(anyString());
        verify(play).fetch(eq("abcdefghijk"), any());
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    public void refreshDoesNotJoinAnOlderInFlightExtraction() {
        InfoCache cache = mock(InfoCache.class);
        AuthContextFactory auth = mock(AuthContextFactory.class);
        when(auth.create(anyString())).thenReturn(new ExtractionSession().getAuth());
        List<Runnable> work = new ArrayList<>();
        YoutubeExtractor extractor = new YoutubeExtractor(mock(Fetch.class), mock(Fetch.class),
                cache, work::add, new Gson(), auth);
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        extractor.getInfo(url, new ExtractionSession());
        extractor.refreshInfo(url, new ExtractionSession());
        assertEquals(2, work.size());
    }

    @Test
    public void cancelledRefreshDoesNotStartNetworkWork() {
        List<Runnable> work = new ArrayList<>();
        YoutubeExtractor extractor = new YoutubeExtractor(mock(Fetch.class), mock(Fetch.class),
                mock(InfoCache.class), work::add, new Gson(), mock(AuthContextFactory.class));
        ExtractionSession session = new ExtractionSession();
        session.cancel();
        assertTrue(extractor.refreshInfo("https://www.youtube.com/watch?v=abcdefghijk", session)
                .isCompletedExceptionally());
        assertTrue(work.isEmpty());
    }
}
