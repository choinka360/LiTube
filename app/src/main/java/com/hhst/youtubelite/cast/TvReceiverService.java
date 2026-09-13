package com.hhst.youtubelite.cast;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.nsd.*;
import android.os.*;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import com.google.gson.*;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.*;
import com.hhst.youtubelite.player.engine.TvPlaybackSources;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

@AndroidEntryPoint
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class TvReceiverService extends Service {
    public interface Screen { void changed(); void showCastFullscreen(); void confirmPair(String name, Runnable approve, Runnable deny); }
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
    private long generation, pairingUntil;
    private String pendingNonce, approvedToken, pendingName;
    private long pendingUntil;
    private ExtractionSession extraction;
    private boolean refreshed, destroyed, desiredPlaying, pendingCastFullscreen;
    private String currentId, title = "Ready to receive", error = "";
    private long startPosition;
    private final Set<String> recentCommands = new LinkedHashSet<>();
    public String currentVideoId() { return currentId; }
    public ExoPlayer player() { return player; }
    public String description() { return error.isEmpty() ? title : error; }
    public String pairingCode() { return identity == null ? "Starting secure receiver…" : identity.fingerprint().substring(0, 16).replaceAll("(.{4})(?!$)", "$1-"); }
    public int port() { return server == null ? CastProtocol.PORT : server.port(); }
    public void attach(Screen value) { screen = new WeakReference<>(value); value.changed(); showPendingCast(); }
    public void detach(Screen value) { if (screen.get() == value) screen.clear(); }
    public void beginPairing() { pairingUntil = SystemClock.elapsedRealtime() + 120_000; notifyScreen(); }
    public void forgetPhones() { getSharedPreferences("litube_cast", 0).edit().remove("tokens").apply(); pendingNonce = approvedToken = null; notifyScreen(); }
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("litube_tv", "TV casting", NotificationManager.IMPORTANCE_LOW));
        startForeground(8642, notification("Casting receiver is starting"));
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
            @Override public void onEvents(Player ignored, Player.Events events) {
                mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                        .setState(player.isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, player.getCurrentPosition(), 1f).build());
                notifyScreen();
            }
            @Override public void onPlayerError(PlaybackException failure) {
                if (!refreshed && currentId != null) { refreshed = true; load(currentId, player.getCurrentPosition(), true); }
                else { error = "Playback failed. Try sending the clip again. " + failure.getErrorCodeName(); notifyScreen(); }
            }
        });
        network.execute(() -> {
            try {
                CastTls.Identity created = CastTls.identity();
                CastServer opened = new CastServer(created, CastProtocol.PORT, (path, token, body) -> onMain(() -> route(path, token, body)));
                main.post(() -> { if (destroyed) { opened.close(); return; } identity = created; server = opened; advertise(); notifyScreen(); });
            } catch (Exception e) { main.post(() -> { error = "Receiver could not start. Close other LiTube TV sessions and try again."; notifyScreen(); }); }
        });
    }
    private <T> T onMain(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); main.post(task);
        try { return task.get(3, TimeUnit.SECONDS); }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
        finally { if (!task.isDone()) { main.removeCallbacks(task); task.cancel(false); } }
    }
    private JsonObject route(String path, String token, JsonObject body) {
        if (destroyed) throw new IllegalStateException();
        if (path.equals("/pair")) {
            if (screen.get() == null || SystemClock.elapsedRealtime() > pairingUntil) throw new IllegalArgumentException("Choose Pair a phone on the TV first");
            String nonce = text(body, "nonce", 64), name = text(body, "name", 60);
            if (!nonce.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid pairing request");
            if (pendingNonce != null && SystemClock.elapsedRealtime() < pendingUntil) throw new IllegalArgumentException("A pairing request is already open on the TV");
            pendingNonce = nonce; pendingName = name; approvedToken = null; pendingUntil = SystemClock.elapsedRealtime() + 90_000;
            screen.get().confirmPair(name, () -> {
                if (!nonce.equals(pendingNonce) || SystemClock.elapsedRealtime() > pendingUntil) return;
                approvedToken = CastProtocol.randomToken();
                Set<String> tokens = new HashSet<>(getSharedPreferences("litube_cast", 0).getStringSet("tokens", Set.of()));
                if (tokens.size() >= 8) tokens.clear();
                tokens.add(CastProtocol.hash(approvedToken));
                getSharedPreferences("litube_cast", 0).edit().putStringSet("tokens", tokens).apply();
                pairingUntil = 0;
            }, () -> { if (nonce.equals(pendingNonce)) { pendingNonce = null; approvedToken = null; } });
            return CastClient.body("state", "pending");
        }
        if (path.equals("/pair-status")) {
            if (!text(body, "nonce", 64).equals(pendingNonce) || SystemClock.elapsedRealtime() > pendingUntil) throw new IllegalArgumentException("Pairing expired or declined; try again");
            JsonObject result = CastClient.body("state", approvedToken == null ? "pending" : "paired");
            if (approvedToken != null) { result.addProperty("token", approvedToken); result.addProperty("pin", identity.fingerprint()); }
            return result;
        }
        if (token.length() != 43 || !getSharedPreferences("litube_cast", 0).getStringSet("tokens", Set.of()).contains(CastProtocol.hash(token))) throw new SecurityException();
        if ((path.equals("/pause") || path.equals("/resume")) && body.has("videoId") && !text(body, "videoId", 32).equals(currentId)) throw new IllegalArgumentException("The TV is playing a different video");
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
            case "/resume" -> { desiredPlaying = true; player.play(); }
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
            case "/stop" -> { generation++; if (extraction != null) extraction.cancel(); player.stop(); title = "Ready to receive"; notifyScreen(); }
            case "/wake" -> wakeAndShow();
            case "/status" -> { }
            default -> throw new IllegalArgumentException("Unknown command");
        }
        JsonObject result = CastClient.body("title", description());
        result.addProperty("positionMs", player.getCurrentPosition()); result.addProperty("playing", player.isPlaying());
        result.addProperty("playWhenReady", desiredPlaying);
        result.addProperty("needsOpen", screen.get() == null);
        result.addProperty("height", player.getVideoFormat() == null ? 0 : player.getVideoFormat().height);
        result.addProperty("bufferMs", player.getTotalBufferedDuration());
        result.addProperty("videoId", currentId == null ? "" : currentId); TvRemoteControls.status(player, result); return result;
    }
    private String text(JsonObject body, String key, int max) { if (!body.has(key) || !body.get(key).isJsonPrimitive()) throw new IllegalArgumentException("Missing " + key); String value = body.get(key).getAsString(); if (value.length() > max) throw new IllegalArgumentException("Value too long"); return value; }
    public void playLocal(String input) { refreshed = false; load(CastProtocol.videoId(input), 0, false); }
    private void load(String id, long position, boolean refresh) {
        if (!refresh) desiredPlaying = true;
        long request = ++generation; currentId = id; startPosition = position; error = ""; title = "Loading video…";
        if (extraction != null) extraction.cancel(); extraction = new ExtractionSession(); notifyScreen();
        var future = refresh ? extractor.refreshInfo("https://www.youtube.com/watch?v=" + id, extraction) : extractor.getInfo("https://www.youtube.com/watch?v=" + id, extraction);
        future.whenComplete((details, failure) -> main.post(() -> {
            if (destroyed || request != generation) return;
            if (failure != null) { error = "Could not load this video. Check the TV's internet connection and try again."; notifyScreen(); return; }
            try { player.setMediaSource(TvPlaybackSources.create(details)); player.seekTo(position); player.prepare(); player.setPlayWhenReady(desiredPlaying); title = details.video().getTitle();
                getSharedPreferences("litube_tv_suggestions", MODE_PRIVATE).edit().putString("last_video", id).apply(); }
            catch (Exception e) { error = "This video has no playable stream."; }
            notifyScreen();
        }));
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
        getSystemService(NotificationManager.class).notify(8642, notification("Video received — open LiTube TV"));
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 8642, new Intent(this, TvActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 8643, new Intent(this, TvReceiverService.class).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, "litube_tv").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("LiTube TV").setContentText(text)
                .setContentIntent(open).setOngoing(true).addAction(0, "Disable casting", stop).build();
    }
    private void advertise() {
        nsd = getSystemService(NsdManager.class);
        NsdServiceInfo info = new NsdServiceInfo(); info.setServiceName("LiTube " + Build.MODEL); info.setServiceType(CastProtocol.SERVICE_TYPE); info.setPort(port());
        registration = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) {}
            public void onRegistrationFailed(NsdServiceInfo i, int code) { main.post(() -> { error = "Automatic discovery unavailable; connect using the TV IP address."; notifyScreen(); }); }
            public void onServiceUnregistered(NsdServiceInfo i) {}
            public void onUnregistrationFailed(NsdServiceInfo i, int code) {}
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }
    private void showPendingCast() {
        Screen target = screen.get();
        if (pendingCastFullscreen && target != null) { pendingCastFullscreen = false; target.showCastFullscreen(); }
    }
    private void notifyScreen() { if (screen.get() != null) screen.get().changed(); }
    @Override public int onStartCommand(Intent intent, int flags, int id) { if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; } return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; generation++; instance = null; screen.clear(); if (extraction != null) extraction.cancel();
        if (server != null) server.close(); network.shutdownNow();
        if (nsd != null && registration != null) try { nsd.unregisterService(registration); } catch (Exception ignored) {}
        if (player != null) player.release(); if (mediaSession != null) mediaSession.release(); stopForeground(true); super.onDestroy();
    }
}


