package com.hhst.youtubelite.cast;

import android.content.Context;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;

public final class CastClient implements AutoCloseable {
    @androidx.annotation.Keep public static final class Device {
        public String name = "LiTube TV", host = "", pin = "", token = "", mac = "";
        public int port = CastProtocol.PORT;
        public boolean paired() { return token != null && !token.isEmpty() && pin != null && pin.length() == 64; }
        public boolean sameTv(Device other) {
            if (other == null) return false;
            if (pin != null && other.pin != null && pin.length() >= 16 && other.pin.length() >= 16) return pin.substring(0, 16).equalsIgnoreCase(other.pin.substring(0, 16));
            return host.equals(other.host) && port == other.port;
        }
    }
    /** The TV no longer accepts this phone's credential, for example after Remove linked devices. */
    public static final class Unauthorized extends IllegalArgumentException { Unauthorized(String message) { super(message); } }
    private final Device device;
    private final OkHttpClient client;
    private final AtomicReference<String> seen = new AtomicReference<>();
    public CastClient(Device device) throws Exception { this(device, false); }
    public CastClient(Device device, boolean longPoll) throws Exception { validateHost(device.host); this.device = device; this.client = CastTls.client(device.pin, longPoll); }
    private CastClient(Device device, Void firstContact) throws Exception { validateHost(device.host); this.device = device; this.client = CastTls.firstContact(seen::set); }
    /** A connection to an unpaired TV discovered on this network; see {@link CastTls#firstContact}. */
    public static CastClient firstContact(Device device) throws Exception { return new CastClient(device, (Void) null); }
    /** Fingerprint of the certificate presented on a first-contact connection. */
    public String seenFingerprint() { return seen.get(); }
    public static void validateHost(String host) throws Exception {
        if (host == null || host.isBlank() || host.contains("/") || host.contains("@")) throw new IllegalArgumentException("Enter the TV's local IP address");
        InetAddress address = InetAddress.getByName(host);
        byte[] bytes = address.getAddress();
        boolean ula = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        if (!address.isSiteLocalAddress() && !address.isLinkLocalAddress() && !address.isLoopbackAddress() && !ula) throw new IllegalArgumentException("Casting requires a local-network address");
    }
    public JsonObject request(String path, JsonObject body) throws Exception {
        if (device.port < 1 || device.port > 65535) throw new IllegalArgumentException("Invalid TV port");
        HttpUrl url = new HttpUrl.Builder().scheme("https").host(device.host).port(device.port).addPathSegment(path).build();
        Request request = new Request.Builder().url(url).header("Authorization", "Bearer " + device.token)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
        try (Response response = client.newCall(request).execute()) {
            JsonObject result = JsonParser.parseString(response.body().string()).getAsJsonObject();
            String error = result.has("error") ? result.get("error").getAsString() : "TV returned " + response.code();
            if (response.code() == 401) throw new Unauthorized(error);
            if (!response.isSuccessful() || result.has("error")) throw new IllegalArgumentException(error);
            return result;
        }
    }
    /** The most recently used TV. */
    public static Device load(Context context) {
        try { return new Gson().fromJson(context.getSharedPreferences("litube_cast", 0).getString("device", "null"), Device.class); }
        catch (Exception ignored) { return null; }
    }
    /** Remembers a TV and makes it the most recently used one. */
    public static void save(Context context, Device device) {
        List<Device> all = devices(context);
        all.removeIf(device::sameTv); all.add(0, device);
        while (all.size() > 8) all.remove(all.size() - 1);
        context.getSharedPreferences("litube_cast", 0).edit().putString("device", new Gson().toJson(device)).putString("devices", new Gson().toJson(all)).apply();
    }
    /** Every remembered TV, most recently used first. */
    public static List<Device> devices(Context context) {
        List<Device> result = new ArrayList<>();
        try {
            List<Device> saved = new Gson().fromJson(context.getSharedPreferences("litube_cast", 0).getString("devices", "[]"), new TypeToken<List<Device>>() {}.getType());
            if (saved != null) for (Device d : saved) if (d != null && d.host != null) result.add(d);
        } catch (Exception ignored) { }
        Device last = load(context);
        if (last != null && last.host != null && result.stream().noneMatch(last::sameTv)) result.add(0, last);
        return result;
    }
    public static void forget(Context context, Device device) {
        List<Device> all = devices(context); all.removeIf(device::sameTv);
        var edit = context.getSharedPreferences("litube_cast", 0).edit().putString("devices", new Gson().toJson(all));
        if (device.sameTv(load(context))) edit.remove("device");
        edit.apply();
    }
    public static JsonObject body(String key, String value) { JsonObject result = new JsonObject(); result.addProperty(key, value); return result; }
    @Override public void close() { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
}
