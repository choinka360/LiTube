package com.hhst.youtubelite.cast;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.leanback.widget.HorizontalGridView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hhst.youtubelite.R;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen TV player. With the overlay hidden: OK pauses, left/right skip 10 seconds, up/down
 * show the controls and Back leaves the player. With it shown, the remote moves between controls.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class TvPlayerUi {
    interface Host {
        TvReceiverService service();
        void play(TvVideo video);
    }
    private static final long SEEK_STEP_MS = 10_000, HIDE_AFTER_MS = 5_000;
    private final Activity activity;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View overlay;
    private final PlayerView video;
    private final DefaultTimeBar timebar;
    private final TextView title, channel, time, message, seekHint;
    private final ProgressBar buffering;
    private final ImageButton play;
    private final HorizontalGridView upNext;
    private final View upNextLabel;
    private List<TvVideo> shownUpNext;
    private boolean scrubbing, wasPaused;
    private final Runnable hide = this::hideOverlay;
    private final Runnable hideHint = () -> seekHintVisible(false);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (overlay.getVisibility() == View.VISIBLE) main.postDelayed(this, 500);
        }
    };

    TvPlayerUi(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        overlay = activity.findViewById(R.id.tv_overlay);
        video = activity.findViewById(R.id.tv_video);
        timebar = activity.findViewById(R.id.tv_timebar);
        title = activity.findViewById(R.id.tv_player_title);
        channel = activity.findViewById(R.id.tv_player_channel);
        time = activity.findViewById(R.id.tv_time);
        message = activity.findViewById(R.id.tv_player_message);
        seekHint = activity.findViewById(R.id.tv_seek_hint);
        buffering = activity.findViewById(R.id.tv_buffering);
        play = activity.findViewById(R.id.tv_btn_play);
        upNext = activity.findViewById(R.id.tv_up_next);
        upNextLabel = activity.findViewById(R.id.tv_up_next_label);
        TvCards.alignStart(upNext);
        timebar.setKeyTimeIncrement(SEEK_STEP_MS);
        timebar.addListener(new TimeBar.OnScrubListener() {
            @Override public void onScrubStart(@NonNull TimeBar bar, long position) { scrubbing = true; main.removeCallbacks(hide); }
            @Override public void onScrubMove(@NonNull TimeBar bar, long position) { showTime(position); }
            @Override public void onScrubStop(@NonNull TimeBar bar, long position, boolean canceled) {
                scrubbing = false;
                ExoPlayer player = player();
                if (!canceled && player != null) player.seekTo(position);
                scheduleHide();
            }
        });
        play.setOnClickListener(v -> togglePlay());
        activity.findViewById(R.id.tv_btn_restart).setOnClickListener(v -> { ExoPlayer p = player(); if (p != null) { p.seekTo(0); p.play(); } scheduleHide(); });
        activity.findViewById(R.id.tv_btn_next).setOnClickListener(v -> {
            TvReceiverService service = host.service();
            if (service == null || !service.playNext()) Toast.makeText(activity, "Nothing up next yet", Toast.LENGTH_SHORT).show();
        });
        activity.findViewById(R.id.tv_btn_captions).setOnClickListener(v -> tracks("subtitles"));
        activity.findViewById(R.id.tv_btn_quality).setOnClickListener(v -> tracks("quality"));
        activity.findViewById(R.id.tv_btn_speed).setOnClickListener(v -> tracks("speed"));
        View.OnFocusChangeListener keepAlive = (v, focused) -> { if (focused) scheduleHide(); };
        for (int id : new int[]{R.id.tv_btn_play, R.id.tv_btn_restart, R.id.tv_btn_next, R.id.tv_btn_captions, R.id.tv_btn_quality, R.id.tv_btn_speed, R.id.tv_timebar})
            activity.findViewById(id).setOnFocusChangeListener(keepAlive);
    }

    private ExoPlayer player() { TvReceiverService service = host.service(); return service == null ? null : service.player(); }
    void attach(ExoPlayer player) { video.setPlayer(player); update(); }
    void detach() { video.setPlayer(null); main.removeCallbacksAndMessages(null); }
    boolean overlayVisible() { return overlay.getVisibility() == View.VISIBLE; }

    void showOverlay(View focus) {
        overlay.setVisibility(View.VISIBLE);
        (focus == null ? play : focus).requestFocus();
        main.removeCallbacks(tick); main.post(tick);
        scheduleHide();
    }
    void showOverlay() { showOverlay(null); }
    void hideOverlay() {
        if (scrubbing) return;
        overlay.setVisibility(View.GONE);
        main.removeCallbacks(hide); main.removeCallbacks(tick);
    }
    /** Hides the controls after a while, but only while the video plays, like YouTube. */
    private void scheduleHide() {
        main.removeCallbacks(hide);
        ExoPlayer player = player();
        if (player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED) main.postDelayed(hide, HIDE_AFTER_MS);
    }

    /** Handles remote keys while the player is open. Returns false for Back when the player should close. */
    boolean dispatch(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        ExoPlayer player = player();
        switch (key) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && event.getRepeatCount() == 0 && player != null) {
                    if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) togglePlay(); else if (key == KeyEvent.KEYCODE_MEDIA_PLAY) player.play(); else player.pause();
                    if (!overlayVisible()) showOverlay();
                }
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) seekBy(key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? SEEK_STEP_MS : -SEEK_STEP_MS);
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && event.getRepeatCount() == 0 && host.service() != null) host.service().playNext(); return true; }
            case KeyEvent.KEYCODE_BACK -> {
                if (!overlayVisible()) return false;
                if (!down) hideOverlay();
                return true;
            }
            default -> { }
        }
        if (overlayVisible()) { if (down) scheduleHide(); return false; }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.getRepeatCount() == 0) { togglePlay(); showOverlay(); }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) seekBy(key == KeyEvent.KEYCODE_DPAD_RIGHT ? SEEK_STEP_MS : -SEEK_STEP_MS);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down && event.getRepeatCount() == 0) showOverlay(key == KeyEvent.KEYCODE_DPAD_DOWN && upNext.getChildCount() > 0 ? upNext : null);
                return true;
            }
            default -> { return false; }
        }
    }

    private void togglePlay() {
        ExoPlayer player = player();
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_ENDED) { player.seekTo(0); player.play(); }
        else if (player.getPlayWhenReady()) player.pause(); else player.play();
        update(); scheduleHide();
    }

    private void seekBy(long delta) {
        ExoPlayer player = player();
        if (player == null || !player.isCurrentMediaItemSeekable()) return;
        long duration = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + delta);
        if (duration > 0) target = Math.min(target, duration);
        player.seekTo(target);
        seekHint.setText((delta > 0 ? "+" : "−") + Math.abs(delta / 1000) + " s   " + TvVideo.clock(target / 1000));
        seekHintVisible(true);
        main.removeCallbacks(hideHint); main.postDelayed(hideHint, 900);
        updateProgress();
    }
    private void seekHintVisible(boolean value) { seekHint.setVisibility(value ? View.VISIBLE : View.GONE); }

    /** Re-reads playback state from the receiver. */
    void update() {
        TvReceiverService service = host.service();
        ExoPlayer player = player();
        if (service == null || player == null) return;
        title.setText(service.title());
        channel.setText(service.author());
        String error = service.error();
        message.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);
        message.setText(error);
        boolean loading = error.isEmpty() && service.currentVideoId() != null && (player.getPlaybackState() == Player.STATE_BUFFERING || player.getPlaybackState() == Player.STATE_IDLE);
        buffering.setVisibility(loading ? View.VISIBLE : View.GONE);
        boolean ended = player.getPlaybackState() == Player.STATE_ENDED;
        play.setImageResource(ended ? R.drawable.ic_replay : player.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
        // Paused or finished: keep the controls up, as YouTube does.
        boolean paused = !player.getPlayWhenReady() || ended;
        if (paused && !wasPaused && !overlayVisible() && service.currentVideoId() != null && error.isEmpty()) showOverlay();
        // Playing again (for example after autoplay): let the controls hide as usual.
        if (!paused && wasPaused && overlayVisible()) scheduleHide();
        wasPaused = paused;
        List<TvVideo> next = service.upNext();
        if (next != shownUpNext) {
            shownUpNext = next;
            upNext.setAdapter(new TvCards.CardAdapter(next, new TvCards.Listener() {
                @Override public void open(TvVideo item) { host.play(item); }
                @Override public void focused(TvVideo item) { scheduleHide(); }
            }));
            upNextLabel.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
            upNext.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        }
        updateProgress();
    }

    private void updateProgress() {
        ExoPlayer player = player();
        if (player == null || scrubbing) return;
        long duration = Math.max(0, player.getDuration());
        timebar.setDuration(duration);
        timebar.setPosition(player.getCurrentPosition());
        timebar.setBufferedPosition(player.getBufferedPosition());
        showTime(player.getCurrentPosition());
    }
    private void showTime(long position) {
        ExoPlayer player = player();
        long duration = player == null ? 0 : Math.max(0, player.getDuration());
        time.setText(TvVideo.clock(position / 1000) + " / " + TvVideo.clock(duration / 1000));
    }

    /** Captions, quality and speed menus, built from the tracks the TV player actually has. */
    private void tracks(String kind) {
        ExoPlayer player = player();
        TvReceiverService service = host.service();
        if (player == null || service == null) return;
        main.removeCallbacks(hide);
        JsonObject state = new JsonObject();
        TvRemoteControls.status(player, state);
        List<String> labels = new ArrayList<>(), ids = new ArrayList<>();
        int selected = 0;
        if (kind.equals("speed")) {
            for (float speed : new float[]{0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f}) {
                if (Math.abs(state.get("speed").getAsFloat() - speed) < 0.01f) selected = labels.size();
                labels.add(speed == 1f ? "Normal" : (speed + "x").replace(".0x", "x")); ids.add(Float.toString(speed));
            }
        } else {
            boolean quality = kind.equals("quality");
            labels.add(quality ? "Auto" : "Off"); ids.add(quality ? "auto" : "off");
            JsonArray items = state.getAsJsonArray(quality ? "qualities" : "subtitles");
            for (JsonElement element : items) {
                JsonObject item = element.getAsJsonObject();
                if (item.get("selected").getAsBoolean()) selected = labels.size();
                labels.add(item.get("label").getAsString()); ids.add(item.get("id").getAsString());
            }
            if (quality && state.get("qualityAuto").getAsBoolean()) selected = 0;
            if (!quality && state.get("subtitlesOff").getAsBoolean()) selected = 0;
            if (items.isEmpty()) { Toast.makeText(activity, quality ? "Only automatic quality is available" : "No captions for this video", Toast.LENGTH_SHORT).show(); scheduleHide(); return; }
        }
        new AlertDialog.Builder(activity).setTitle(kind.equals("speed") ? "Playback speed" : kind.equals("quality") ? "Quality" : "Captions")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                    try {
                        if (kind.equals("speed")) TvRemoteControls.speed(player, Float.parseFloat(ids.get(which)));
                        else TvRemoteControls.select(player, kind.equals("quality") ? androidx.media3.common.C.TRACK_TYPE_VIDEO : androidx.media3.common.C.TRACK_TYPE_TEXT, ids.get(which));
                    } catch (IllegalArgumentException e) { Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show(); }
                    dialog.dismiss();
                })
                .setOnDismissListener(d -> scheduleHide()).show();
    }
}
