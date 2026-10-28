package com.hhst.youtubelite.cast;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.nsd.*;
import android.os.*;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.*;
import com.hhst.youtubelite.player.engine.TvPlaybackSources;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Owns TV playback and the casting endpoint, independent of the TV screen's lifecycle. */
@AndroidEntryPoint
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class TvReceiverService extends Service {
    public interface Screen { void changed(); void showCastFullscreen(); void confirmPair(String name, String code, Runnable approve, Runnable deny); }
    public static volatile TvReceiverService instance;
    @Inject YoutubeExtractor extractor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private WeakReference<Screen> screen = new WeakReference<>(null);
    private volatile CastServer server;
    private CastTls.Identity identity;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private MediaSessionCompat mediaSession;
    private ExoPlayer player;
    private long generation;
    private String pendingNonce, approvedToken, pendingName;
    private long pendingUntil, pairBlockedUntil;
    private boolean pendingPairPrompt;
    private ExtractionSession extraction;
    private boolean refreshed, destroyed, desiredPlaying, pendingCastFullscreen;
    private String currentId, title = "Ready to cast", author = "", error = "";
    private List<TvVideo> upNext = List.of();
    private final Set<String> recentCommands = new LinkedHashSet<>();
    // Status long-polls wait on this until playback state changes.
    private final Object changes = new Object();
    private volatile long version;
    public String currentVideoId() { return currentId; }
    public ExoPlayer player() { return player; }
    public String description() { return error.isEmpty() ? title : error; }
    public String title() { return currentId == null ? "" : title; }
    public String author() { return author; }
    public String error() { return error; }
    public List<TvVideo> upNext() { return upNext; }
    public String pairingCode() { return identity == null ? "Starting…" : identity.fingerprint().substring(0, 16).replaceAll("(.{4})(?!$)", "$1-"); }
    public int port() { return server == null ? CastProtocol.PORT : server.port(); }
    public boolean ready() { return server != null; }
    public String tvName() { return tvName(this); }
    public static String tvName(Context context) {
        String name = null;
        try { name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (Exception ignored) { }
        return name == null || name.isBlank() ? Build.MODEL : name;
    }
    public void attach(Screen value) { screen = new WeakReference<>(value); value.changed(); showPendingCast(); showPendingPair(); }
    public void detach(Screen value) { if (screen.get() == value) screen.clear(); }
    public void forgetPhones() { getSharedPreferences("litube_cast", 0).edit().remove("tokens").apply(); pendingNonce = approvedToken = null; publish(); }
    public static boolean autoplay(Context context) { return context.getSharedPreferences("litube_tv", 0).getBoolean("autoplay", true); }
    public static void setAutoplay(Context context, boolean value) { context.getSharedPreferences("litube_tv", 0).edit().putBoolean("autoplay", value).apply(); }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("litube_tv", "TV casting", NotificationManager.IMPORTANCE_LOW));
        startForeground(8642, notification("Ready to cast from your phone"));
        player = new ExoPlayer.Builder(this).setLoadControl(TvPlaybackSources.loadControl()).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        mediaSession = new MediaSessionCompat(this, "LiTube TV");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { player.play(); }
            @Override public void onPause() { player.pause(); }
            @Override public void onSeekTo(long value) { player.seekTo(Math.max(0, value)); }
            @Override public void onStop() { player.stop(); }
        });
        mediaSession.setActive(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) { desiredPlaying = ready; }
            @Override public void onPlaybackStateChanged(int state) { if (state == Player.STATE_ENDED && autoplay(TvReceiverService.this)) playNext(); }
            @Override public void onEvents(Player ignored, Player.Events events) {
                mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                        .setState(player.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, player.getCurrentPosition(), 1f).build());
                publish();
            }
            @Override public void onPlayerError(PlaybackException failure) {
                if (!refreshed && currentId != null) { refreshed = true; load(currentId, player.getCurrentPosition(), true); }
                else { error = "Playback failed. Try casting the video again. " + failure.getErrorCodeName(); publish(); }
            }
        });
        network.execute(() -> {
            try {
                CastTls.Identity created = CastTls.identity();
                CastServer opened = new CastServer(created, CastProtocol.PORT, (path, token, body) -> {
                    if (path.equals("/status") && body.has("since")) { authorize(token); awaitChange(body.get("since").getAsLong()); }
                    return onMain(() -> route(path, token, body));
                });
                main.post(() -> { if (destroyed) { opened.close(); return; } identity = created; server = opened; advertise(); publish(); });
            } catch (Exception e) { main.post(() -> { error = "Casting could not start. Close other LiTube TV sessions and try again."; publish(); }); }
        });
    }
    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); main.post(task);
        try { return task.get(3, TimeUnit.SECONDS); }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
        finally { if (!task.isDone()) { main.removeCallbacks(task); task.cancel(false); } }
    }
    private void authorize(String token) {
        if (token.length() != 43 || !getSharedPreferences("litube_cast", 0).getStringSet("tokens", Set.of()).contains(CastProtocol.hash(token))) throw new SecurityException();
    }
    private void awaitChange(long since) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + 12_000;
        synchronized (changes) {
            while (version == since && !destroyed) {
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) return;
                changes.wait(left);
            }
        }
    }
    private JsonObject route(String path, String token, JsonObject body) {
        if (destroyed) throw new IllegalStateException();
        if (path.equals("/pair")) {
            String nonce = text(body, "nonce", 64), name = text(body, "name", 60);
            if (!nonce.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid pairing request");
            long now = SystemClock.elapsedRealtime();
            if (identity == null) throw new IllegalArgumentException("The TV is still starting; try again");
            if (now < pairBlockedUntil) throw new IllegalArgumentException("The TV declined. Try again in a few seconds");
            if (pendingNonce != null && now < pendingUntil) {
                if (nonce.equals(pendingNonce)) return CastClient.body("state", "pending");
                throw new IllegalArgumentException("Another device is waiting for approval on the TV");
            }
            pendingNonce = nonce; pendingName = name; approvedToken = null; pendingUntil = now + 90_000; pendingPairPrompt = true;
            if (screen.get() == null) openForPairing(); else showPendingPair();
            return CastClient.body("state", "pending");
        }
        if (path.equals("/pair-status")) {
            if (!text(body, "nonce", 64).equals(pendingNonce) || SystemClock.elapsedRealtime() > pendingUntil) throw new IllegalArgumentException("Pairing expired or declined; try again");
            JsonObject result = CastClient.body("state", approvedToken == null ? "pending" : "paired");
            if (approvedToken != null) { result.addProperty("token", approvedToken); result.addProperty("pin", identity.fingerprint()); }
            return result;
        }
        authorize(token);
        if ((path.equals("/pause") || path.equals("/resume") || path.equals("/seek")) && body.has("videoId") && !text(body, "videoId", 32).equals(currentId)) throw new IllegalArgumentException("The TV is playing a different video");
        switch (path) {
            case "/play" -> {
                String id = CastProtocol.videoId(text(body, "videoId", 2048));
                long position = CastProtocol.position(body.has("positionMs") ? body.get("positionMs").getAsLong() : 0);
                String request = text(body, "requestId", 64);
                if (request.isEmpty()) throw new IllegalArgumentException("Missing request ID");
                if (recentCommands.add(request)) {
                    if (recentCommands.size() > 32) recentCommands.remove(recentCommands.iterator().next());
                    pendingCastFullscreen = true; showPendingCast();
                    refreshed = false; load(id, position, false); wakeAndShow();
                }
            }
            case "/pause" -> { desiredPlaying = false; player.pause(); }
            case "/resume" -> {
                desiredPlaying = true;
                if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
                player.play();
            }
            case "/seek" -> {
                if (!player.isCurrentMediaItemSeekable()) throw new IllegalArgumentException("This video is not seekable yet");
                long target = CastProtocol.position(body.get("positionMs").getAsLong());
                player.seekTo(player.getDuration() > 0 ? Math.min(target, player.getDuration()) : target);
            }
            case "/speed" -> TvRemoteControls.speed(player, body.get("speed").getAsFloat());
            case "/quality", "/subtitles" -> {
                if (!text(body, "videoId", 32).equals(currentId)) throw new IllegalArgumentException("The TV video changed. Open the controls again.");
                TvRemoteControls.select(player, path.equals("/quality") ? C.TRACK_TYPE_VIDEO : C.TRACK_TYPE_TEXT, text(body, "track", 32));
            }
            case "/volume" -> {
                int delta = body.has("delta") ? body.get("delta").getAsInt() : 0;
                if (delta < -1 || delta > 1) throw new IllegalArgumentException("Invalid volume step");
                AudioManager audio = getSystemService(AudioManager.class);
                if (delta != 0) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            }
            case "/stop" -> stopPlayback();
            case "/wake" -> wakeAndShow();
            case "/status" -> { }
            default -> throw new IllegalArgumentException("Unknown command");
        }
        return status();
    }
    private JsonObject status() {
        JsonObject result = CastClient.body("title", description());
        result.addProperty("positionMs", player.getCurrentPosition()); result.addProperty("playing", player.isPlaying());
        result.addProperty("playWhenReady", desiredPlaying);
        result.addProperty("needsOpen", screen.get() == null);
        result.addProperty("height", player.getVideoFormat() == null ? 0 : player.getVideoFormat().height);
        result.addProperty("bufferMs", player.getTotalBufferedDuration());
        result.addProperty("videoId", currentId == null ? "" : currentId); TvRemoteControls.status(player, result);
        result.addProperty("name", tvName()); result.addProperty("author", author);
        result.addProperty("ended", player.getPlaybackState() == Player.STATE_ENDED);
        result.addProperty("loading", currentId != null && error.isEmpty() && player.getPlaybackState() != Player.STATE_READY && player.getPlaybackState() != Player.STATE_ENDED);
        result.addProperty("version", version);
        AudioManager audio = getSystemService(AudioManager.class);
        result.addProperty("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC)); result.addProperty("maxVolume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return result;
    }
    private String text(JsonObject body, String key, int max) { if (!body.has(key) || !body.get(key).isJsonPrimitive()) throw new IllegalArgumentException("Missing " + key); String value = body.get(key).getAsString(); if (value.length() > max) throw new IllegalArgumentException("Value too long"); return value; }
    public void playLocal(String input) { refreshed = false; load(CastProtocol.videoId(input), 0, false); }
    public void stopPlayback() { generation++; if (extraction != null) extraction.cancel(); player.stop(); currentId = null; title = "Ready to cast"; author = ""; error = ""; upNext = List.of(); publish(); }
    /** Plays the first suggestion after the current video, as YouTube's autoplay does. */
    public boolean playNext() {
        // Related lists usually point back to the previous video; prefer one not watched recently.
        Set<String> recent = new HashSet<>();
        List<TvVideo> history = history(this);
        for (TvVideo video : history.subList(0, Math.min(10, history.size()))) recent.add(video.id());
        for (TvVideo video : upNext) if (!video.id().equals(currentId) && !recent.contains(video.id())) { playLocal(video.id()); return true; }
        for (TvVideo video : upNext) if (!video.id().equals(currentId)) { playLocal(video.id()); return true; }
        return false;
    }
    private void load(String id, long position, boolean refresh) {
        if (!refresh) desiredPlaying = true;
        long request = ++generation; boolean changed = !id.equals(currentId);
        currentId = id; error = ""; title = "Loading video…";
        // Drop the previous video at once, so neither screen keeps showing its picture or position.
        if (changed) { author = ""; upNext = List.of(); player.stop(); player.clearMediaItems(); loadUpNext(id, request); }
        if (extraction != null) extraction.cancel(); extraction = new ExtractionSession(); publish();
        var future = refresh ? extractor.refreshInfo("https://www.youtube.com/watch?v=" + id, extraction) : extractor.getInfo("https://www.youtube.com/watch?v=" + id, extraction);
        future.whenComplete((details, failure) -> main.post(() -> {
            if (destroyed || request != generation) return;
            if (failure != null) {
                android.util.Log.w("LiTubeCast", "Could not load " + id, failure);
                title = "Couldn't play this video"; error = "Could not load this video. Check the TV's internet connection and try again."; publish(); return;
            }
            try {
                player.setMediaSource(TvPlaybackSources.create(details)); player.seekTo(position); player.prepare(); player.setPlayWhenReady(desiredPlaying);
                title = details.video().getTitle(); author = details.video().getAuthor() == null ? "" : details.video().getAuthor();
                Long seconds = details.video().getDuration();
                remember(TvVideo.of(id, title, author, seconds == null ? 0 : seconds));
            } catch (Exception e) { android.util.Log.w("LiTubeCast", "No playable stream for " + id, e); title = "Couldn't play this video"; error = "This video has no playable stream."; }
            publish();
        }));
    }
    private void loadUpNext(String id, long request) {
        network.execute(() -> {
            List<TvVideo> found;
            try { found = TvSuggestions.related(id); } catch (Exception e) { found = List.of(); }
            List<TvVideo> result = found;
            main.post(() -> { if (!destroyed && id.equals(currentId) && request <= generation) { upNext = result; publish(); } });
        });
    }
    /** Recently watched videos on this TV, newest first. */
    public static List<TvVideo> history(Context context) {
        try {
            List<TvVideo> saved = new Gson().fromJson(context.getSharedPreferences("litube_tv_suggestions", MODE_PRIVATE).getString("history", "[]"), new TypeToken<List<TvVideo>>() {}.getType());
            return saved == null ? new ArrayList<>() : new ArrayList<>(saved);
        } catch (Exception e) { return new ArrayList<>(); }
    }
    private void remember(TvVideo video) {
        List<TvVideo> all = history(this); all.removeIf(v -> v == null || v.id().equals(video.id())); all.add(0, video);
        while (all.size() > 30) all.remove(all.size() - 1);
        getSharedPreferences("litube_tv_suggestions", MODE_PRIVATE).edit().putString("last_video", video.id()).putString("history", new Gson().toJson(all)).apply();
    }
    /** Unauthenticated, so unlike {@link #wakeAndShow} it never turns the TV on. */
    private void openForPairing() {
        if (getSystemService(PowerManager.class).isInteractive() && (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this))) {
            try { startActivity(new Intent(this, TvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); } catch (Exception ignored) { }
        }
        getSystemService(NotificationManager.class).notify(8642, notification("A device wants to cast — open LiTube TV to allow it"));
    }
    @SuppressWarnings("deprecation") private void wakeAndShow() {
        try {
            PowerManager manager = getSystemService(PowerManager.class);
            PowerManager.WakeLock wake = manager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "litube:cast-wake");
            wake.acquire(5000);
        } catch (Exception ignored) { }
        if (screen.get() != null || Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) {
            try { startActivity(new Intent(this, TvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); } catch (Exception ignored) { }
        }
        if (screen.get() == null) getSystemService(NotificationManager.class).notify(8642, notification("Your phone is casting — open LiTube TV"));
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 8642, new Intent(this, TvActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 8643, new Intent(this, TvReceiverService.class).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, "litube_tv").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("LiTube TV").setContentText(text)
                .setContentIntent(open).setOngoing(true).addAction(0, "Turn off casting", stop).build();
    }
    private void advertise() {
        nsd = getSystemService(NsdManager.class);
        NsdServiceInfo info = new NsdServiceInfo(); info.setServiceName(tvName()); info.setServiceType(CastProtocol.SERVICE_TYPE); info.setPort(port());
        info.setAttribute("id", CastProtocol.deviceId(identity.fingerprint())); info.setAttribute("v", "2");
        registration = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) {}
            public void onRegistrationFailed(NsdServiceInfo i, int code) { main.post(() -> { error = "Automatic discovery unavailable; connect using the TV IP address."; publish(); }); }
            public void onServiceUnregistered(NsdServiceInfo i) {}
            public void onUnregistrationFailed(NsdServiceInfo i, int code) {}
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }
    private void showPendingCast() {
        Screen target = screen.get();
        if (pendingCastFullscreen && target != null) { pendingCastFullscreen = false; target.showCastFullscreen(); }
    }
    private void showPendingPair() {
        Screen target = screen.get();
        if (!pendingPairPrompt || target == null || identity == null || pendingNonce == null) return;
        if (SystemClock.elapsedRealtime() > pendingUntil) { pendingPairPrompt = false; return; }
        pendingPairPrompt = false;
        String nonce = pendingNonce;
        target.confirmPair(pendingName, CastProtocol.verificationCode(identity.fingerprint(), nonce), () -> {
            if (!nonce.equals(pendingNonce) || SystemClock.elapsedRealtime() > pendingUntil) return;
            approvedToken = CastProtocol.randomToken();
            Set<String> tokens = new HashSet<>(getSharedPreferences("litube_cast", 0).getStringSet("tokens", Set.of()));
            if (tokens.size() >= 8) tokens.clear();
            tokens.add(CastProtocol.hash(approvedToken));
            getSharedPreferences("litube_cast", 0).edit().putStringSet("tokens", tokens).apply();
        }, () -> { if (nonce.equals(pendingNonce)) { pendingNonce = null; approvedToken = null; pairBlockedUntil = SystemClock.elapsedRealtime() + 5000; } });
    }
    private void publish() {
        synchronized (changes) { version++; changes.notifyAll(); }
        if (screen.get() != null) screen.get().changed();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; } return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; generation++; instance = null; screen.clear(); if (extraction != null) extraction.cancel();
        synchronized (changes) { changes.notifyAll(); }
        if (server != null) server.close(); network.shutdownNow();
        if (nsd != null && registration != null) try { nsd.unregisterService(registration); } catch (Exception ignored) {}
        if (player != null) player.release(); if (mediaSession != null) mediaSession.release(); stopForeground(true); super.onDestroy();
    }
}
