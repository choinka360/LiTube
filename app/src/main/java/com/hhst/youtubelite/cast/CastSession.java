package com.hhst.youtubelite.cast;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;

/** Links explicit phone-player controls to the acknowledged TV casting session. */
public final class CastSession {
    private static CastSession instance;
    public static synchronized CastSession get(Context context) {
        if (instance == null) instance = new CastSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners = new HashSet<>();
    private CastClient.Device device;
    private String videoId = "";
    private boolean playing, polling;
    private long generation;
    private CastSession(Context context) { this.context = context; }
    public boolean controls(String id) { return device != null && id != null && id.equals(videoId); }
    public boolean playing() { return playing; }
    public void addListener(Runnable listener) { listeners.add(listener); schedule(); }
    public void removeListener(Runnable listener) { listeners.remove(listener); if (listeners.isEmpty()) main.removeCallbacks(poll); }
    public void acknowledge(CastClient.Device target, String command, JsonObject state) {
        main.post(() -> {
            if (command.equals("play")) { device = target; generation++; }
            if (device == null || !device.host.equals(target.host) || device.port != target.port) return;
            if (command.equals("stop")) { disconnect(); return; }
            update(state); schedule();
        });
    }
    public void disconnect() { device = null; videoId = ""; generation++; main.removeCallbacks(poll); changed(); }
    private void update(JsonObject state) {
        if (state.has("videoId")) videoId = state.get("videoId").getAsString();
        playing = state.has("playWhenReady") ? state.get("playWhenReady").getAsBoolean() : state.get("playing").getAsBoolean();
        changed();
    }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    public boolean setPlaying(String id, boolean value) {
        if (!controls(id)) return false;
        CastClient.Device target = device; long requestGeneration = generation;
        worker.execute(() -> {
            try (CastClient client = new CastClient(target)) {
                JsonObject body = CastClient.body("videoId", id);
                JsonObject state = client.request(value ? "resume" : "pause", body);
                main.post(() -> { if (generation == requestGeneration) update(state); });
            } catch (Exception e) {
                main.post(() -> { if (generation == requestGeneration) Toast.makeText(context, "TV did not confirm " + (value ? "resume" : "pause") + ". Check the TV connection.", Toast.LENGTH_LONG).show(); });
            }
        });
        return true;
    }
    private void schedule() { main.removeCallbacks(poll); if (device != null && !listeners.isEmpty()) main.postDelayed(poll, 2000); }
    private final Runnable poll = () -> {
        if (device == null || listeners.isEmpty() || polling) return;
        polling = true; CastClient.Device target = device; long requestGeneration = generation;
        worker.execute(() -> {
            JsonObject response = null;
            try (CastClient client = new CastClient(target)) { response = client.request("status", new JsonObject()); } catch (Exception ignored) {}
            JsonObject state = response;
            main.post(() -> { polling = false; if (generation == requestGeneration && state != null) update(state); schedule(); });
        });
    };
}
