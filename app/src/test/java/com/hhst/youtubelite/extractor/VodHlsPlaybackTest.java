package com.hhst.youtubelite.extractor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Test;
import org.schabi.newpipe.extractor.stream.StreamType;
import java.lang.reflect.Method;
import java.util.List;

public class VodHlsPlaybackTest {
    @Test public void retainsOnDemandHlsManifestFromExtractor() throws Exception {
        StreamCatalog catalog = new StreamCatalog();
        StreamCandidate manifest = StreamCandidate.hlsManifest("https://example.com/master.m3u8", "IOS", false, false, false);
        catalog.getManifestCandidates().add(manifest);
        YoutubeExtractor extractor = mock(YoutubeExtractor.class, CALLS_REAL_METHODS);
        Method method = YoutubeExtractor.class.getDeclaredMethod("buildDeliveries", StreamCatalog.class);
        method.setAccessible(true);
        DeliveryCatalog deliveries = (DeliveryCatalog) method.invoke(extractor, catalog);
        assertSame(manifest, deliveries.first(PlaybackMode.VOD_HLS).getManifest());
        PlaybackPlan plan = PlaybackPlanner.plan(deliveries);
        assertEquals(PlaybackMode.VOD_HLS, plan.getMode());
        assertEquals(manifest.getUrl(), plan.getManifestUrl());
    }
    @Test public void prefersAvailableHlsOverProgressiveDelivery() {
        DeliveryCatalog catalog = new DeliveryCatalog();
        Delivery adaptive = new Delivery();
        adaptive.setMode(PlaybackMode.ADAPTIVE);
        Delivery hls = new Delivery();
        hls.setMode(PlaybackMode.VOD_HLS);
        hls.setManifest(StreamCandidate.hlsManifest("https://example.com/master.m3u8", "IOS", false, false, false));
        catalog.setItems(List.of(adaptive, hls));
        assertSame(hls, PlaybackPlanner.plan(catalog).getDelivery());
    }
    @Test public void livePlaybackStillUsesLiveHls() {
        DeliveryCatalog catalog = new DeliveryCatalog();
        catalog.setStreamType(StreamType.LIVE_STREAM);
        Delivery live = new Delivery();
        live.setMode(PlaybackMode.LIVE_HLS);
        catalog.setItems(List.of(live));
        assertEquals(PlaybackMode.LIVE_HLS, PlaybackPlanner.plan(catalog).getMode());
    }
}