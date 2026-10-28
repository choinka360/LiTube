package com.hhst.youtubelite.cast;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.gson.JsonObject;
import com.hhst.youtubelite.R;
import java.util.*;
import java.util.concurrent.*;

/**
 * The phone's connection to one TV, like YouTube's cast session: while connected, every video the
 * phone opens plays on the TV and the phone player controls it.
 */
public final class CastSession {
    public interface Callback { void done(String error); }
    private static final String CHANNEL = "litube_cast_remote";
    private static final int NOTIFICATION = 8644;
    private static CastSession instance;
    public static synchronized CastSession get(Context context) {
        if (instance == null) instance = new CastSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // A finished session's long-poll may still be waiting, so each session polls on its own thread.
    private final ExecutorService poller = Executors.newCachedThreadPool();
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private CastClient.Device device;
    private String videoId = "", title = "", author = "", connecting = "";
    private boolean playing, playWhenReady, ended, loading;
    private long positionMs, durationMs, stateAt;
    private float speed = 1f;
    private volatile long generation;
    // Video sent with play but not yet confirmed; stale status replies must not replace it.
    private String pendingCast;
    private String handoffId;
    private long handoffPosition = -1;
    private CastSession(Context context) {
        this.context = context;
        // A notification left by an earlier process has no session behind it.
        NotificationManagerCompat.from(context).cancel(NOTIFICATION);
    }

    public boolean connected() { return device != null; }
    /** True when the TV is playing this video, so the phone player acts as its remote. */
    public boolean controls(String id) { return device != null && id != null && !id.isEmpty() && id.equals(videoId); }
    public boolean playing() { return playing; }
    public boolean playWhenReady() { return playWhenReady; }
    public boolean ended() { return ended; }
    public boolean loading() { return loading; }
    public String deviceName() { return device == null ? "" : device.name; }
    public CastClient.Device device() { return device; }
    public String videoId() { return videoId; }
    public String title() { return title; }
    /** Name of the TV currently being connected to, or empty. */
    public String connecting() { return connecting; }
    public long duration() { return durationMs; }
    /** TV position, advanced locally between status updates. */
    public long position() {
        long value = positionMs;
        if (playing && stateAt > 0) value += (long) ((SystemClock.elapsedRealtime() - stateAt) * speed);
        return durationMs > 0 ? Math.min(value, durationMs) : value;
    }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    /**
     * Connects to a paired TV and, when a video is given, starts it there at the given position.
     * Retries for a while so a TV that is waking from standby can answer.
     */
    public void connect(CastClient.Device target, String id, long position, Callback callback) {
        connecting = target.name; changed();
        worker.execute(() -> {
            String failure = null;
            try {
                if (target.mac != null && !target.mac.isEmpty()) try { WakeOnLan.send(target.mac); } catch (Exception ignored) { }
                JsonObject body = new JsonObject(); String command = "status";
                if (id != null) { command = "play"; body.addProperty("videoId", id); body.addProperty("positionMs", Math.max(0, position)); body.addProperty("requestId", UUID.randomUUID().toString()); }
                JsonObject state = retrying(target, command, body, 20_000);
                if (state.has("name")) target.name = state.get("name").getAsString();
                CastClient.save(context, target);
                String finalCommand = command;
                main.post(() -> acknowledge(target, "play", state, finalCommand.equals("play")));
            } catch (CastClient.Unauthorized e) {
                // The TV revoked this phone; the caller asks the TV to approve it again.
                target.token = ""; CastClient.save(context, target); failure = "Allow this phone on the TV again.";
            } catch (Exception e) { failure = message(e); }
            String error = failure;
            main.post(() -> { connecting = ""; changed(); if (callback != null) callback.done(error); });
        });
    }
    private static JsonObject retrying(CastClient.Device target, String command, JsonObject body, long patience) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + patience;
        try (CastClient client = new CastClient(target)) {
            while (true) {
                try { return client.request(command, body); }
                catch (java.io.IOException e) { if (SystemClock.elapsedRealtime() >= deadline || e instanceof javax.net.ssl.SSLException) throw e; Thread.sleep(1500); }
            }
        }
    }
    static String message(Exception e) {
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException || e instanceof java.io.InterruptedIOException) return "The TV didn't respond. Make sure LiTube is open on the TV and both devices use the same Wi-Fi.";
        if (e instanceof javax.net.ssl.SSLException) return "This TV's identity changed (was LiTube reinstalled?). Long-press it, choose Forget, then connect again.";
        return e.getMessage() == null ? "Couldn't reach the TV" : e.getMessage();
    }
    /** Records a TV's response to a command sent by this phone. */
    public void acknowledge(CastClient.Device target, String command, JsonObject state) { main.post(() -> acknowledge(target, command, state, command.equals("play"))); }
    private void acknowledge(CastClient.Device target, String command, JsonObject state, boolean startedVideo) {
        if (command.equals("play")) {
            boolean fresh = device == null || !device.sameTv(target);
            device = target;
            if (fresh) { generation++; startPolling(); }
        }
        if (device == null || !device.sameTv(target)) return;
        if (command.equals("stop")) { disconnect(); return; }
        update(state);
        if (startedVideo && state.has("videoId")) videoId = state.get("videoId").getAsString();
    }
    /** Casts another video to the connected TV. Does nothing if the TV already plays it. */
    public void cast(String id, long position) {
        if (device == null || id == null || id.equals(videoId)) return;
        videoId = id; pendingCast = id; title = ""; positionMs = Math.max(0, position); stateAt = SystemClock.elapsedRealtime(); playing = false; playWhenReady = true; ended = false; loading = true; changed();
        JsonObject body = CastClient.body("videoId", id); body.addProperty("positionMs", Math.max(0, position)); body.addProperty("requestId", UUID.randomUUID().toString());
        send("play", body, "TV did not start the video");
    }
    public boolean setPlaying(String id, boolean value) {
        if (!controls(id)) return false;
        playWhenReady = value; if (!value) { positionMs = position(); playing = false; stateAt = SystemClock.elapsedRealtime(); } changed();
        send(value ? "resume" : "pause", CastClient.body("videoId", id), "TV did not confirm " + (value ? "play" : "pause"));
        return true;
    }
    public boolean seek(String id, long position) {
        if (!controls(id)) return false;
        long target = Math.max(0, durationMs > 0 ? Math.min(position, durationMs) : position);
        positionMs = target; stateAt = SystemClock.elapsedRealtime(); ended = false; changed();
        JsonObject body = CastClient.body("videoId", id); body.addProperty("positionMs", target);
        send("seek", body, "TV did not seek");
        return true;
    }
    public void volume(int delta) { if (device != null) { JsonObject body = new JsonObject(); body.addProperty("delta", delta); send("volume", body, null); } }
    /**
     * Ends the session like YouTube's "Stop casting": the TV stops, and the phone continues the
     * video from the TV's position.
     */
    public void stopCasting() {
        if (device == null) return;
        if (!videoId.isEmpty() && !ended) { handoffId = videoId; handoffPosition = position(); }
        send("stop", new JsonObject(), null);
        disconnect();
    }
    /** Returns the position to continue this video locally after casting stopped, or -1. */
    public long takeHandoff(String id) {
        if (id == null || !id.equals(handoffId)) return -1;
        long result = handoffPosition; handoffId = null; handoffPosition = -1; return result;
    }
    public void disconnect() {
        device = null; videoId = ""; title = ""; pendingCast = null; playing = playWhenReady = ended = loading = false; generation++;
        NotificationManagerCompat.from(context).cancel(NOTIFICATION);
        changed();
    }
    private void send(String command, JsonObject body, String failure) {
        CastClient.Device target = device; long requestGeneration = generation;
        if (target == null) return;
        worker.execute(() -> {
            try (CastClient client = new CastClient(target)) {
                JsonObject state = client.request(command, body);
                main.post(() -> { if (generation != requestGeneration || command.equals("stop")) return; if (command.equals("play")) pendingCast = null; update(state); });
            } catch (Exception e) {
                main.post(() -> {
                    if (generation != requestGeneration) return;
                    if (command.equals("play")) { pendingCast = null; loading = false; changed(); }
                    if (failure != null) Toast.makeText(context, failure + ". " + message(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    private void update(JsonObject state) {
        if (pendingCast != null) return;
        if (state.has("videoId")) videoId = state.get("videoId").getAsString();
        if (state.has("title")) title = state.get("title").getAsString();
        if (state.has("author")) author = state.get("author").getAsString();
        if (state.has("name") && device != null) device.name = state.get("name").getAsString();
        playing = state.has("playing") && state.get("playing").getAsBoolean();
        playWhenReady = state.has("playWhenReady") ? state.get("playWhenReady").getAsBoolean() : playing;
        ended = state.has("ended") && state.get("ended").getAsBoolean();
        loading = state.has("loading") && state.get("loading").getAsBoolean();
        if (state.has("positionMs")) { positionMs = state.get("positionMs").getAsLong(); stateAt = SystemClock.elapsedRealtime(); }
        if (state.has("durationMs") && state.get("durationMs").getAsLong() > 0) durationMs = state.get("durationMs").getAsLong();
        if (state.has("speed")) speed = state.get("speed").getAsFloat();
        changed();
    }
    private void changed() {
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
        notification();
    }
    /** Follows the TV's state with long-polls; the TV answers as soon as anything changes. */
    private void startPolling() {
        long session = generation; CastClient.Device target = device;
        poller.execute(() -> {
            long since = -1; int failures = 0;
            try (CastClient client = new CastClient(target, true)) {
                while (generation == session && !Thread.currentThread().isInterrupted()) {
                    try {
                        JsonObject body = new JsonObject(); if (since >= 0) body.addProperty("since", since);
                        JsonObject state = client.request("status", body);
                        failures = 0;
                        boolean waits = state.has("version");
                        since = waits ? state.get("version").getAsLong() : -1;
                        main.post(() -> { if (generation == session) update(state); });
                        if (!waits) Thread.sleep(1000);
                    } catch (InterruptedException e) { return; }
                    catch (Exception e) {
                        if (e instanceof CastClient.Unauthorized || ++failures >= 8) { main.post(() -> { if (generation == session) { disconnect(); Toast.makeText(context, "Lost connection to the TV", Toast.LENGTH_LONG).show(); } }); return; }
                        try { Thread.sleep(2000); } catch (InterruptedException interrupted) { return; }
                    }
                }
            } catch (Exception ignored) { }
        });
    }
    private void notification() {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (device == null || !manager.areNotificationsEnabled()) return;
        context.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_cast_connected)
                .setContentTitle(title.isEmpty() || videoId.isEmpty() ? "Connected to " + device.name : title)
                .setContentText(videoId.isEmpty() ? "Pick a video to play it on the TV" : "Casting to " + device.name)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) builder.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (!videoId.isEmpty()) builder.addAction(playWhenReady ? R.drawable.ic_pause : R.drawable.ic_play, playWhenReady ? "Pause" : "Play", action(playWhenReady ? CastActionReceiver.PAUSE : CastActionReceiver.PLAY));
        builder.addAction(R.drawable.ic_close, "Stop casting", action(CastActionReceiver.STOP));
        try { manager.notify(NOTIFICATION, builder.build()); } catch (SecurityException ignored) { }
    }
    private PendingIntent action(String name) {
        return PendingIntent.getBroadcast(context, name.hashCode(), new Intent(context, CastActionReceiver.class).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
