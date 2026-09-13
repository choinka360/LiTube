package com.hhst.youtubelite.player.engine;

import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import com.hhst.youtubelite.extractor.PlaybackDetails;

/** Shared playback construction for the phone and native TV player. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class TvPlaybackSources {
    private TvPlaybackSources() {}
    public static LoadControl loadControl() { return PlayerLoadControl.create(); }
    public static MediaSource create(PlaybackDetails details) { return PlaybackSourceFactory.create(new PlayerDataSource(null), details, details.plan()); }
}

