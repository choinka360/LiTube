package com.hhst.youtubelite.cast;

import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Track-selection contract test independent of YouTube caption availability. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class TvRemoteControlsDeviceTest {
    @Test public void subtitlesPreserveVideoOverrideAndOffCanBeReenabled() {
        TrackGroup video = new TrackGroup("video", new Format.Builder().setSampleMimeType("video/avc").setHeight(720).build());
        TrackGroup text = new TrackGroup("text", new Format.Builder().setSampleMimeType("text/vtt").setLanguage("en").build());
        Tracks tracks = new Tracks(ImmutableList.of(
            new Tracks.Group(video, false, new int[]{C.FORMAT_HANDLED}, new boolean[]{true}),
            new Tracks.Group(text, false, new int[]{C.FORMAT_HANDLED}, new boolean[]{false})));
        AtomicReference<TrackSelectionParameters> params = new AtomicReference<>(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT);
        ExoPlayer player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class[]{ExoPlayer.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getCurrentTracks": return tracks;
                case "getTrackSelectionParameters": return params.get();
                case "setTrackSelectionParameters": params.set((TrackSelectionParameters) args[0]); return null;
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        TvRemoteControls.select(player, C.TRACK_TYPE_VIDEO, "0:0");
        TvRemoteControls.select(player, C.TRACK_TYPE_TEXT, "1:0");
        assertTrue(params.get().overrides.containsKey(video)); assertTrue(params.get().overrides.containsKey(text));
        TvRemoteControls.select(player, C.TRACK_TYPE_TEXT, "off");
        assertTrue(params.get().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)); assertTrue(params.get().overrides.containsKey(video));
        TvRemoteControls.select(player, C.TRACK_TYPE_TEXT, "1:0");
        assertFalse(params.get().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
        TvRemoteControls.select(player, C.TRACK_TYPE_VIDEO, "auto");
        assertTrue(params.get().overrides.containsKey(text)); assertFalse(params.get().overrides.containsKey(video));
        assertThrows(IllegalArgumentException.class, () -> TvRemoteControls.select(player, C.TRACK_TYPE_TEXT, "8:9"));
        assertThrows(IllegalArgumentException.class, () -> TvRemoteControls.speed(player, Float.NaN));
    }
}
