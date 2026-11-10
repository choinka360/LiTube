package com.hhst.youtubelite.cast;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonObject;
import com.hhst.youtubelite.R;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** YouTube-style "Cast to" sheet: TVs on this Wi-Fi, one tap to connect, approval on the TV. */
public final class CastPicker {
    private CastPicker() {}

    /** Keeps a cast button's icon in step with the connection. */
    public static void bindButton(ImageButton button) {
        CastSession session = CastSession.get(button.getContext());
        Runnable refresh = () -> {
            button.setImageResource(session.connected() ? R.drawable.ic_cast_connected : R.drawable.ic_cast);
            button.setAlpha(session.connecting().isEmpty() ? 0.92f : 0.5f);
        };
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { session.addListener(refresh); refresh.run(); }
            @Override public void onViewDetachedFromWindow(View v) { session.removeListener(refresh); }
        });
        if (button.isAttachedToWindow()) session.addListener(refresh);
        refresh.run();
    }

    public static void show(Activity activity, @Nullable String videoId, long positionMs, @Nullable Runnable dismissed) {
        new Sheet(activity, videoId, positionMs, dismissed).show();
    }

    private static final class Entry {
        String key, name, host = "", id = "";
        int port = CastProtocol.PORT;
        CastClient.Device saved;
        boolean found;
        View row;
    }

    private static final class Sheet {
        private final Activity activity;
        private final String videoId;
        private final long position;
        private final BottomSheetDialog dialog;
        private final CastSession session;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
        private final View root;
        private final LinearLayout devices;
        private final TextView status;
        private final Runnable sessionChanged = this::render;
        private NsdManager nsd;
        private NsdManager.DiscoveryListener discovery;
        private WifiManager.MulticastLock multicast;
        private boolean resolving, closed, busy;

        Sheet(Activity activity, String videoId, long position, Runnable dismissed) {
            this.activity = activity; this.videoId = videoId; this.position = position;
            session = CastSession.get(activity);
            dialog = new BottomSheetDialog(activity);
            root = LayoutInflater.from(activity).inflate(R.layout.sheet_cast, null, false);
            dialog.setContentView(root);
            devices = root.findViewById(R.id.cast_devices);
            status = root.findViewById(R.id.cast_status);
            root.findViewById(R.id.cast_manual).setOnClickListener(v -> manual());
            root.findViewById(R.id.cast_stop).setOnClickListener(v -> { session.stopCasting(); dialog.dismiss(); });
            root.findViewById(R.id.cast_play_pause).setOnClickListener(v -> session.setPlaying(session.videoId(), !session.playWhenReady()));
            root.findViewById(R.id.cast_volume_up).setOnClickListener(v -> session.volume(1));
            root.findViewById(R.id.cast_volume_down).setOnClickListener(v -> session.volume(-1));
            dialog.setOnDismissListener(d -> {
                closed = true; stopDiscovery(); session.removeListener(sessionChanged); worker.shutdownNow(); main.removeCallbacksAndMessages(null);
                if (dismissed != null) dismissed.run();
            });
            for (CastClient.Device saved : CastClient.devices(activity)) {
                Entry entry = new Entry(); entry.saved = saved; entry.name = saved.name; entry.host = saved.host; entry.port = saved.port;
                entry.key = saved.pin != null && saved.pin.length() >= 16 ? CastProtocol.deviceId(saved.pin) : saved.host + ":" + saved.port;
                entry.id = saved.pin != null && saved.pin.length() >= 16 ? entry.key : "";
                entries.put(entry.key, entry);
            }
        }

        void show() {
            session.addListener(sessionChanged);
            render();
            dialog.show();
            if (!session.connected()) discover();
        }

        private void render() {
            if (closed) return;
            boolean connected = session.connected();
            ((TextView) root.findViewById(R.id.cast_title)).setText(connected ? "Connected to " + session.deviceName() : activity.getString(R.string.cast_to_tv));
            root.findViewById(R.id.cast_connected).setVisibility(connected ? View.VISIBLE : View.GONE);
            root.findViewById(R.id.cast_manual).setVisibility(connected ? View.GONE : View.VISIBLE);
            root.findViewById(R.id.cast_help).setVisibility(connected ? View.GONE : View.VISIBLE);
            devices.setVisibility(connected ? View.GONE : View.VISIBLE);
            root.findViewById(R.id.cast_searching).setVisibility(!connected && discovery != null ? View.VISIBLE : View.GONE);
            if (connected) {
                String title = session.title();
                ((TextView) root.findViewById(R.id.cast_now_playing)).setText(session.videoId().isEmpty() ? "Pick a video on your phone to play it on the TV." : title);
                ((ImageButton) root.findViewById(R.id.cast_play_pause)).setImageResource(session.playWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
                root.findViewById(R.id.cast_play_pause).setVisibility(session.videoId().isEmpty() ? View.INVISIBLE : View.VISIBLE);
                return;
            }
            for (Entry entry : entries.values()) {
                if (entry.row == null) {
                    entry.row = LayoutInflater.from(activity).inflate(R.layout.item_cast_device, devices, false);
                    entry.row.setOnClickListener(v -> connect(entry));
                    entry.row.setOnLongClickListener(v -> { forget(entry); return true; });
                    devices.addView(entry.row);
                }
                ((TextView) entry.row.findViewById(R.id.device_name)).setText(entry.name);
                boolean busyHere = entry.name.equals(session.connecting());
                String away = entry.saved != null && entry.saved.paired() && RemoteLink.configured() ? "Away from home: tap to wake it and play" : "Not found on this network yet";
                String detail = busyHere ? (session.progress().isEmpty() ? "Connecting…" : session.progress()) : entry.found ? (entry.saved != null && entry.saved.paired() ? "Ready" : "Available on your Wi-Fi") : away;
                ((TextView) entry.row.findViewById(R.id.device_detail)).setText(detail);
                entry.row.findViewById(R.id.device_progress).setVisibility(busyHere ? View.VISIBLE : View.GONE);
                entry.row.setAlpha(entry.found || entry.saved != null ? 1f : 0.6f);
            }
        }

        private void message(String text) {
            if (closed) return;
            status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
            status.setText(text);
        }

        private void connect(Entry entry) {
            if (busy) return;
            if (entry.saved != null && entry.saved.paired()) {
                entry.saved.host = entry.host; entry.saved.port = entry.port;
                start(entry.saved);
            } else pair(entry.host, entry.port, entry.name, null);
        }

        /** Connects to a paired TV and starts the current video there. */
        private void start(CastClient.Device device) {
            busy = true; message(null);
            session.connect(device, videoId, position, error -> {
                busy = false;
                if (closed) return;
                if (error == null) { dialog.dismiss(); return; }
                // Revoked on the TV: ask it to approve this phone again.
                if (!device.paired()) { pair(device.host, device.port, device.name, null); return; }
                message(error);
            });
        }

        /**
         * First connection. With no code the TV is found on this Wi-Fi and its certificate is pinned
         * once the owner allows the phone on the TV; both screens show the same 4-digit code.
         */
        private void pair(String host, int port, String name, @Nullable String code) {
            busy = true; message("Connecting to " + name + "…");
            CastClient.Device device = new CastClient.Device(); device.host = host; device.port = port; device.name = name;
            worker.execute(() -> {
                String failure = null;
                try {
                    if (code != null) device.pin = CastProtocol.normalizeCode(code);
                    try (CastClient client = code == null ? CastClient.firstContact(device) : new CastClient(device)) {
                        String nonce = CastProtocol.randomToken();
                        JsonObject body = CastClient.body("nonce", nonce); body.addProperty("name", phoneName(activity));
                        client.request("pair", body);
                        String seen = client.seenFingerprint();
                        String verify = seen == null ? "" : "\n\nMake sure the TV shows the code " + CastProtocol.verificationCode(seen, nonce) + ".";
                        main.post(() -> message("On your TV, select Allow to let this phone cast." + verify));
                        long deadline = SystemClock.elapsedRealtime() + 90_000;
                        while (true) {
                            if (SystemClock.elapsedRealtime() > deadline) throw new IllegalArgumentException("The TV didn't respond in time. Try again.");
                            JsonObject result = client.request("pair-status", CastClient.body("nonce", nonce));
                            if ("paired".equals(result.get("state").getAsString())) {
                                String pin = result.get("pin").getAsString().toUpperCase(Locale.ROOT);
                                String now = client.seenFingerprint();
                                if (now != null && (!pin.equalsIgnoreCase(now) || !pin.equalsIgnoreCase(seen))) throw new IllegalArgumentException("The TV's identity changed during pairing. Try again.");
                                if (code != null && !pin.startsWith(device.pin)) throw new IllegalArgumentException("The TV code doesn't match.");
                                device.pin = pin; device.token = result.get("token").getAsString();
                                break;
                            }
                            Thread.sleep(1000);
                        }
                    }
                } catch (InterruptedException e) { return; }
                catch (Exception e) { failure = CastSession.message(e); }
                String error = failure;
                main.post(() -> {
                    if (closed) return;
                    busy = false;
                    if (error != null) { message(error); return; }
                    CastClient.save(activity, device);
                    start(device);
                });
            });
        }

        private void forget(Entry entry) {
            if (entry.saved == null) return;
            new MaterialAlertDialogBuilder(activity).setTitle("Forget " + entry.name + "?")
                    .setMessage("This phone will need to be allowed on the TV again before it can cast.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Forget", (d, w) -> {
                        CastClient.forget(activity, entry.saved); entry.saved = null;
                        if (!entry.found) { devices.removeView(entry.row); entries.remove(entry.key); }
                        render();
                    }).show();
        }

        /** For networks that block discovery: the TV's address and the code from its settings. */
        private void manual() {
            LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * activity.getResources().getDisplayMetrics().density); form.setPadding(pad, pad / 2, pad, 0);
            EditText host = new EditText(activity); host.setHint("TV IP address, e.g. 192.168.1.12"); host.setSingleLine(true); host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            EditText code = new EditText(activity); code.setHint("16-character TV code"); code.setSingleLine(true); code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            CastClient.Device last = CastClient.load(activity);
            if (last != null) host.setText(last.host);
            TextView help = new TextView(activity); help.setText("On the TV open LiTube > Settings > Link with phone to see its address and code."); help.setPadding(0, pad / 2, 0, 0);
            form.addView(host); form.addView(code); form.addView(help);
            new MaterialAlertDialogBuilder(activity).setTitle("Connect with TV code").setView(form).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Connect", (d, w) -> {
                        String address = host.getText().toString().trim(); int port = CastProtocol.PORT;
                        int colon = address.lastIndexOf(':');
                        if (colon > 0 && address.indexOf(':') == colon) {
                            try { port = Integer.parseInt(address.substring(colon + 1)); address = address.substring(0, colon); } catch (NumberFormatException ignored) { }
                        }
                        String value = code.getText().toString();
                        try { CastProtocol.normalizeCode(value); } catch (IllegalArgumentException e) { message(e.getMessage()); return; }
                        String prefix = CastProtocol.normalizeCode(value);
                        for (CastClient.Device saved : CastClient.devices(activity)) {
                            if (saved.paired() && saved.pin.startsWith(prefix)) { saved.host = address; saved.port = port; start(saved); return; }
                        }
                        pair(address, port, "LiTube TV", value);
                    }).show();
        }

        private void discover() {
            try {
                WifiManager wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) { multicast = wifi.createMulticastLock("litube-discovery"); multicast.setReferenceCounted(false); multicast.acquire(); }
                nsd = activity.getSystemService(NsdManager.class);
                discovery = new NsdManager.DiscoveryListener() {
                    public void onDiscoveryStarted(String type) { }
                    public void onServiceFound(NsdServiceInfo info) { main.post(() -> { if (discovery != null && resolveQueue.size() < 20) { resolveQueue.add(info); resolveNext(); } }); }
                    public void onServiceLost(NsdServiceInfo info) { }
                    public void onDiscoveryStopped(String type) { }
                    public void onStartDiscoveryFailed(String type, int error) { main.post(() -> { stopDiscovery(); render(); message("Couldn't search this network. Use Connect with TV code."); }); }
                    public void onStopDiscoveryFailed(String type, int error) { }
                };
                nsd.discoverServices(CastProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
                main.postDelayed(() -> {
                    stopDiscovery(); render();
                    if (entries.isEmpty()) message("No TVs found. Open LiTube on the TV, check both devices use the same Wi-Fi, or use Connect with TV code.");
                }, 20_000);
            } catch (Exception e) { stopDiscovery(); message("Couldn't search this network. Use Connect with TV code."); }
        }

        @SuppressWarnings("deprecation")
        private void resolveNext() {
            if (discovery == null || resolving || resolveQueue.isEmpty()) return;
            resolving = true;
            nsd.resolveService(resolveQueue.remove(), new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo info, int code) { main.post(() -> { resolving = false; resolveNext(); }); }
                public void onServiceResolved(NsdServiceInfo info) { main.post(() -> { resolving = false; if (!closed && info.getHost() != null) found(info); resolveNext(); }); }
            });
        }

        private void found(NsdServiceInfo info) {
            String host = info.getHost().getHostAddress();
            byte[] raw = info.getAttributes() == null ? null : info.getAttributes().get("id");
            String id = raw == null ? "" : new String(raw, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            Entry entry = id.isEmpty() ? null : entries.get(id);
            if (entry == null) for (Entry e : entries.values()) if (e.host.equals(host) && e.port == info.getPort()) entry = e;
            if (entry == null) { entry = new Entry(); entry.key = id.isEmpty() ? host + ":" + info.getPort() : id; entries.put(entry.key, entry); }
            entry.found = true; entry.host = host; entry.port = info.getPort(); entry.id = id;
            if (entry.saved == null || entry.name == null || entry.name.isEmpty()) entry.name = info.getServiceName();
            message(null); render();
        }

        private void stopDiscovery() {
            if (nsd != null && discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
            discovery = null;
            if (multicast != null && multicast.isHeld()) multicast.release();
            multicast = null;
        }
    }

    static String phoneName(Context context) {
        String name = null;
        try { name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (Exception ignored) { }
        return (name == null || name.isBlank() ? Build.MODEL : name).substring(0, Math.min(60, (name == null || name.isBlank() ? Build.MODEL : name).length()));
    }

}
