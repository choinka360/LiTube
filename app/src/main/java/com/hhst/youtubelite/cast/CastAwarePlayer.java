package com.hhst.youtubelite.cast;

import androidx.annotation.NonNull;
import androidx.media3.common.FlagSet;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The player the phone's player view shows. While a TV plays the video it reports the TV's
 * progress and sends play, pause and seek to the TV, so the normal controls act as a remote.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class CastAwarePlayer extends ForwardingPlayer {
    private final CastSession cast;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    public CastAwarePlayer(@NonNull Player player, @NonNull CastSession cast) { super(player); this.cast = cast; }
    private boolean remote() { return cast.connected() && !cast.videoId().isEmpty(); }
    @Override public void addListener(@NonNull Listener listener) { super.addListener(listener); listeners.add(listener); }
    @Override public void removeListener(@NonNull Listener listener) { super.removeListener(listener); listeners.remove(listener); }
    @Override public boolean isPlaying() { return remote() ? cast.playing() : super.isPlaying(); }
    @Override public boolean getPlayWhenReady() { return remote() ? cast.playWhenReady() : super.getPlayWhenReady(); }
    @Override public int getPlaybackState() {
        if (!remote()) return super.getPlaybackState();
        return cast.ended() ? STATE_ENDED : cast.loading() ? STATE_BUFFERING : STATE_READY;
    }
    @Override public long getCurrentPosition() { return remote() ? cast.position() : super.getCurrentPosition(); }
    @Override public long getContentPosition() { return remote() ? cast.position() : super.getContentPosition(); }
    @Override public long getBufferedPosition() { return remote() ? cast.position() : super.getBufferedPosition(); }
    @Override public long getContentBufferedPosition() { return remote() ? cast.position() : super.getContentBufferedPosition(); }
    @Override public long getDuration() { return remote() && cast.duration() > 0 ? cast.duration() : super.getDuration(); }
    @Override public long getContentDuration() { return remote() && cast.duration() > 0 ? cast.duration() : super.getContentDuration(); }
    @Override public void play() { if (remote()) cast.setPlaying(cast.videoId(), true); else super.play(); }
    @Override public void pause() { if (remote()) cast.setPlaying(cast.videoId(), false); else super.pause(); }
    @Override public void setPlayWhenReady(boolean value) { if (remote()) cast.setPlaying(cast.videoId(), value); else super.setPlayWhenReady(value); }
    @Override public void seekTo(long position) { if (!cast.seek(cast.videoId(), position)) super.seekTo(position); }
    @Override public void seekTo(int index, long position) { if (!cast.seek(cast.videoId(), position)) super.seekTo(index, position); }
    /** Makes the player view re-read play state and progress after the TV reported a change. */
    public void castChanged() {
        Events events = new Events(new FlagSet.Builder().addAll(EVENT_IS_PLAYING_CHANGED, EVENT_PLAYBACK_STATE_CHANGED, EVENT_PLAY_WHEN_READY_CHANGED, EVENT_POSITION_DISCONTINUITY).build());
        for (Listener listener : listeners) listener.onEvents(this, events);
    }
}
