package com.hhst.youtubelite.player.engine;

import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import com.hhst.youtubelite.extractor.PlaybackDetails;

/** Shared playback construction for the phone and native TV player. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class TvPlaybackSources {
    private TvPlaybackSources() {}
    /**
     * The TV has 2 GB of memory shared with Sony's TV services, so it buffers less than the
     * phone (50 s and 32 MB at most instead of 120 s and 64 MB); its connection is steady.
     */
    public static LoadControl loadControl() {
        return new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 2_500, 5_000)
                .setTargetBufferBytes(32 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
    }
    public static MediaSource create(PlaybackDetails details) { return PlaybackSourceFactory.create(new PlayerDataSource(null), details, details.plan()); }
}

