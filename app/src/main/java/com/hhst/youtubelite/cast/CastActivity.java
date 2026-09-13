package com.hhst.youtubelite.cast;

import android.content.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;

public class CastActivity extends AppCompatActivity {
    private EditText host, port, code, link, mac;
    private TextView status;
    private LinearLayout devices;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Button> actions = new ArrayList<>();
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving, destroyed, busy;
    private final Set<String> found = new HashSet<>();
    private volatile CastClient active;
    private long position;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this); LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 40, 32, 24); scroll.addView(root); setContentView(scroll);
        TextView title = new TextView(this); title.setText("Cast to LiTube TV"); title.setTextSize(26); root.addView(title);
        status = new TextView(this); status.setText("Install LiTube on the TV, open it and choose Pair a phone. Both devices must be on the same local network."); root.addView(status);
        link = field(root, "YouTube video link");
        String url = getIntent().getStringExtra("url");
        if (url == null) url = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (url != null) { try { link.setText("https://www.youtube.com/watch?v=" + CastProtocol.videoId(url)); } catch (Exception ignored) { link.setText(url); } }
        position = Math.max(0, getIntent().getLongExtra("positionMs", 0));
        LinearLayout controls = new LinearLayout(this); root.addView(controls);
        button(controls, "Pause", () -> command("pause")); button(controls, "Resume", () -> command("resume")); button(controls, "Stop", () -> command("stop"));
        button(root, "Playback position", () -> remoteOptions("seek"));
        button(root, "Video quality", () -> remoteOptions("quality"));
        button(root, "Subtitles", () -> remoteOptions("subtitles"));
        button(root, "Playback speed", () -> remoteOptions("speed"));
        button(root, "Find TVs on Wi-Fi", this::discover); devices = new LinearLayout(this); devices.setOrientation(LinearLayout.VERTICAL); root.addView(devices);
        host = field(root, "TV local IP address"); port = field(root, "Port"); port.setInputType(2); port.setText(Integer.toString(CastProtocol.PORT));
        code = field(root, "16-character pairing code shown on TV");
        mac = field(root, "Optional TV MAC address for Wake-on-LAN");
        CastClient.Device savedDevice = CastClient.load(this);
        if (savedDevice != null) { host.setText(savedDevice.host); port.setText(Integer.toString(savedDevice.port)); code.setText(savedDevice.pin.substring(0, Math.min(16, savedDevice.pin.length()))); mac.setText(savedDevice.mac); status.setText("Paired with " + savedDevice.name); }
        button(root, "Pair with TV", this::pair);
        button(root, "Cast video / wake TV", () -> command("play"));
        button(root, "Wake TV", () -> command("wake"));
        button(root, "Check TV playback", () -> command("status"));
        button(root, "Forget TV on this phone", () -> { getSharedPreferences("litube_cast", 0).edit().remove("device").apply(); CastSession.get(this).disconnect(); status.setText("TV forgotten. To revoke access, also use Forget paired phones on the TV."); });
        TextView help = new TextView(this); help.setText("Sony Android 9: enable Remote Start in TV network settings. Wake-on-LAN needs the MAC address of the active TV network adapter. Standby wake requires network standby support. The TV streams the video itself; this is LiTube casting, not screen mirroring or Google Cast."); root.addView(help);
    }
    private EditText field(LinearLayout root, String hint) { EditText field = new EditText(this); field.setSingleLine(true); field.setHint(hint); field.setContentDescription(hint); root.addView(field); return field; }
    private void button(LinearLayout root, String title, Runnable action) { Button button = new Button(this); button.setText(title); button.setAllCaps(false); root.addView(button); actions.add(button); button.setOnClickListener(v -> { if (!busy) action.run(); }); }
    private CastClient.Device form() {
        CastClient.Device device = new CastClient.Device(); device.host = host.getText().toString().trim(); device.port = Integer.parseInt(port.getText().toString()); device.pin = CastProtocol.normalizeCode(code.getText().toString()); device.mac = mac.getText().toString().trim();
        if (!device.mac.isEmpty()) WakeOnLan.packet(device.mac);
        CastClient.Device saved = CastClient.load(this);
        if (saved != null && saved.host.equals(device.host) && saved.port == device.port && saved.pin.startsWith(device.pin)) { device.pin = saved.pin; device.token = saved.token; device.name = saved.name; }
        return device;
    }
    private interface Work { String run() throws Exception; }
    private void task(Work work) {
        busy = true; for (Button button : actions) button.setEnabled(false); status.setText("Connecting…");
        worker.execute(() -> { String result; try { result = work.run(); } catch (Exception e) { result = "Could not complete: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
            String message = result; main.post(() -> { if (!destroyed) { status.setText(message); busy = false; for (Button button : actions) button.setEnabled(true); } }); });
    }
    private void pair() {
        try {
            CastClient.Device device = form();
            task(() -> { try (CastClient client = new CastClient(device)) {
                active = client; String nonce = CastProtocol.randomToken();
                JsonObject body = CastClient.body("nonce", nonce); body.addProperty("name", android.os.Build.MODEL);
                client.request("pair", body); main.post(() -> status.setText("Approve this phone on the TV…"));
                long deadline = SystemClock.elapsedRealtime() + 90_000;
                while (!Thread.currentThread().isInterrupted() && SystemClock.elapsedRealtime() < deadline) {
                    JsonObject result = client.request("pair-status", CastClient.body("nonce", nonce));
                    if (result.get("state").getAsString().equals("paired")) { device.token = result.get("token").getAsString(); device.pin = result.get("pin").getAsString(); if (!destroyed) CastClient.save(this, device); return "Paired. Select Cast video to start playback on the TV."; }
                    Thread.sleep(1000);
                }
                throw new IllegalArgumentException("Pairing timed out");
            } finally { active = null; } });
        } catch (Exception e) { status.setText(e.getMessage()); }
    }
    private void command(String command) { command(command, new JsonObject()); }
    private void command(String command, JsonObject body) {
        try {
            CastClient.Device device = form(); if (device.token.isEmpty()) throw new IllegalArgumentException("Pair with this TV first");
            if (command.equals("play")) { body.addProperty("videoId", CastProtocol.videoId(link.getText().toString())); body.addProperty("positionMs", position); body.addProperty("requestId", UUID.randomUUID().toString()); }
            task(() -> {
                boolean wake = command.equals("play") || command.equals("wake");
                if (wake && !device.mac.isEmpty()) WakeOnLan.send(device.mac);
                try (CastClient client = new CastClient(device)) {
                    active = client; long deadline = SystemClock.elapsedRealtime() + (wake ? 45_000 : 1);
                    while (true) {
                        try { JsonObject result = client.request(command, body); CastClient.save(this, device); CastSession.get(this).acknowledge(device, command, result);
                            if (result.has("needsOpen") && result.get("needsOpen").getAsBoolean()) return "TV received the command. Open LiTube on the TV if its firmware prevents automatic opening.";
                            return command.equals("play") ? "Video sent to the TV. Use Pause, Resume or Stop to control it." : playbackStatus(result);
                        } catch (java.io.IOException e) { if (!wake || SystemClock.elapsedRealtime() >= deadline || e instanceof javax.net.ssl.SSLException) throw e; Thread.sleep(2000); }
                    }
                } finally { active = null; }
            });
        } catch (Exception e) { status.setText(e.getMessage()); }
    }
    private String playbackStatus(JsonObject result) {
        String title = result.get("title").getAsString();
        if (!result.has("durationMs")) return "TV: " + title;
        return title + "\n" + (result.get("playing").getAsBoolean() ? "Playing" : "Paused / buffering")
                + " · " + clock(result.get("positionMs").getAsLong()) + " / " + clock(result.get("durationMs").getAsLong())
                + " · " + result.get("height").getAsInt() + "p · " + result.get("speed").getAsFloat() + "x";
    }
    private static String clock(long ms) { long seconds = Math.max(0, ms / 1000); return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
    private void remoteOptions(String kind) {
        try {
            CastClient.Device device = form(); if (device.token.isEmpty()) throw new IllegalArgumentException("Pair with this TV first");
            task(() -> {
                try (CastClient client = new CastClient(device)) {
                    active = client; JsonObject state = client.request("status", new JsonObject());
                    if (!state.has("qualities")) throw new IllegalArgumentException("Update LiTube on the TV to use these controls");
                    main.post(() -> { if (!destroyed && !isFinishing()) showRemoteOptions(kind, state); });
                    return playbackStatus(state);
                } finally { active = null; }
            });
        } catch (Exception e) { status.setText(e.getMessage()); }
    }
    private void showRemoteOptions(String kind, JsonObject state) {
        if (kind.equals("seek")) {
            if (!state.get("seekable").getAsBoolean() || state.get("durationMs").getAsLong() <= 0) { status.setText("This video cannot be seeked yet."); return; }
            long duration = state.get("durationMs").getAsLong();
            LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(32, 16, 32, 16);
            TextView time = new TextView(this); panel.addView(time);
            SeekBar seek = new SeekBar(this); seek.setMax(1000); panel.addView(seek);
            seek.setProgress((int) Math.min(1000, state.get("positionMs").getAsLong() * 1000 / duration));
            time.setText(clock(state.get("positionMs").getAsLong()) + " / " + clock(duration));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean user) { time.setText(clock(duration * value / 1000) + " / " + clock(duration)); }
                public void onStartTrackingTouch(SeekBar bar) {} public void onStopTrackingTouch(SeekBar bar) {}
            });
            new android.app.AlertDialog.Builder(this).setTitle("Playback position").setView(panel).setNegativeButton("Cancel", null)
                    .setPositiveButton("Seek", (d, w) -> { JsonObject body = new JsonObject(); body.addProperty("positionMs", duration * seek.getProgress() / 1000); command("seek", body); }).show();
            return;
        }
        ArrayList<String> labels = new ArrayList<>(), ids = new ArrayList<>(); int selected = 0;
        if (kind.equals("speed")) {
            for (float speed : new float[]{0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f}) {
                if (Math.abs(state.get("speed").getAsFloat() - speed) < 0.01f) selected = labels.size();
                labels.add(speed + "x"); ids.add(Float.toString(speed));
            }
        } else {
            labels.add(kind.equals("quality") ? "Auto" : "Off"); ids.add(kind.equals("quality") ? "auto" : "off");
            JsonArray items = state.getAsJsonArray(kind.equals("quality") ? "qualities" : "subtitles");
            for (JsonElement element : items) {
                JsonObject item = element.getAsJsonObject();
                if (item.get("selected").getAsBoolean()) selected = labels.size();
                labels.add(item.get("label").getAsString()); ids.add(item.get("id").getAsString());
            }
            if (kind.equals("quality") && state.has("qualityAuto") && state.get("qualityAuto").getAsBoolean()) selected = 0;
            if (kind.equals("subtitles") && state.get("subtitlesOff").getAsBoolean()) selected = 0;
            if (items.isEmpty()) status.setText("No selectable " + (kind.equals("quality") ? "video tracks" : "subtitles") + " available for this clip yet.");
        }
        new android.app.AlertDialog.Builder(this).setTitle(kind.equals("speed") ? "Playback speed" : kind.equals("quality") ? "Video quality" : "Subtitles")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (d, which) -> {
                    JsonObject body = new JsonObject();
                    if (kind.equals("speed")) body.addProperty("speed", Float.parseFloat(ids.get(which)));
                    else { body.addProperty("track", ids.get(which)); body.addProperty("videoId", state.get("videoId").getAsString()); }
                    d.dismiss(); command(kind, body);
                }).setNegativeButton("Cancel", null).show();
    }
    private void discover() {
        stopDiscovery(); devices.removeAllViews(); found.clear(); resolveQueue.clear(); resolving = false;
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) { multicast = wifi.createMulticastLock("litube-discovery"); multicast.setReferenceCounted(false); multicast.acquire(); }
            nsd = getSystemService(NsdManager.class);
            discovery = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String type) { main.post(() -> status.setText("Searching for LiTube TVs…")); }
                public void onServiceFound(NsdServiceInfo info) { main.post(() -> { if (discovery != null && found.size() < 20 && found.add(info.getServiceName())) { resolveQueue.add(info); resolveNext(); } }); }
                public void onServiceLost(NsdServiceInfo info) {}
                public void onDiscoveryStopped(String type) {}
                public void onStartDiscoveryFailed(String type, int error) { main.post(() -> { status.setText("Discovery unavailable. Enter the TV IP address manually."); stopDiscovery(); }); }
                public void onStopDiscoveryFailed(String type, int error) {}
            };
            nsd.discoverServices(CastProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
            main.postDelayed(this::stopDiscovery, 30_000);
        } catch (Exception e) { status.setText("Enter the TV IP address manually; Wi-Fi discovery is unavailable."); stopDiscovery(); }
    }
    @SuppressWarnings("deprecation") private void resolveNext() {
        if (discovery == null || resolving || resolveQueue.isEmpty()) return;
        resolving = true;
        nsd.resolveService(resolveQueue.remove(), new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info, int code) { main.post(() -> { resolving = false; resolveNext(); }); }
            public void onServiceResolved(NsdServiceInfo info) { main.post(() -> {
                if (!destroyed && discovery != null && info.getHost() != null) button(devices, info.getServiceName(), () -> { host.setText(info.getHost().getHostAddress()); port.setText(Integer.toString(info.getPort())); status.setText("Enter the pairing code displayed on this TV."); });
                resolving = false; resolveNext();
            }); }
        });
    }
    private void stopDiscovery() {
        if (nsd != null && discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        discovery = null; if (multicast != null && multicast.isHeld()) multicast.release(); multicast = null;
    }
    @Override protected void onStop() { stopDiscovery(); super.onStop(); }
    @Override protected void onDestroy() { destroyed = true; main.removeCallbacksAndMessages(null); stopDiscovery(); if (active != null) active.close(); worker.shutdownNow(); super.onDestroy(); }
}


