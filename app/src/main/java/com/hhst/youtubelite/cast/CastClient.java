package com.hhst.youtubelite.cast;

import android.content.Context;
import com.google.gson.*;
import java.net.*;
import okhttp3.*;

public final class CastClient implements AutoCloseable {
    @androidx.annotation.Keep public static final class Device {
        public String name = "LiTube TV", host = "", pin = "", token = "", mac = "";
        public int port = CastProtocol.PORT;
    }
    private final Device device;
    private final OkHttpClient client;
    public CastClient(Device device) throws Exception { validateHost(device.host); this.device = device; this.client = CastTls.client(device.pin); }
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
            if (!response.isSuccessful() || result.has("error")) throw new IllegalArgumentException(result.has("error") ? result.get("error").getAsString() : "TV returned " + response.code());
            return result;
        }
    }
    public static Device load(Context context) {
        try { return new Gson().fromJson(context.getSharedPreferences("litube_cast", 0).getString("device", "null"), Device.class); }
        catch (Exception ignored) { return null; }
    }
    public static void save(Context context, Device device) { context.getSharedPreferences("litube_cast", 0).edit().putString("device", new Gson().toJson(device)).apply(); }
    public static JsonObject body(String key, String value) { JsonObject result = new JsonObject(); result.addProperty(key, value); return result; }
    @Override public void close() { client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
}
