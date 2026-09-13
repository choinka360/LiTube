package com.hhst.youtubelite.cast;

import android.content.Context;
import android.os.SystemClock;
import com.google.gson.*;
import com.hhst.youtubelite.BuildConfig;
import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Google's supported TV device authorization flow, using LiTube's own registered client. */
final class TvGoogleAccount implements AutoCloseable {
    interface Prompt { void show(String code, String verificationUrl); }
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build();
    private final TvAccountStore store;
    TvGoogleAccount(Context context) { store = new TvAccountStore(context); }
    static boolean configured() { return !BuildConfig.TV_GOOGLE_CLIENT_ID.isBlank() && !BuildConfig.TV_GOOGLE_CLIENT_SECRET.isBlank(); }
    private FormBody.Builder form() { return new FormBody.Builder().add("client_id", BuildConfig.TV_GOOGLE_CLIENT_ID); }
    private JsonObject post(String endpoint, FormBody body) throws Exception {
        Request request = new Request.Builder().url("https://oauth2.googleapis.com/" + endpoint).post(body).build();
        try (Response response = http.newCall(request).execute()) {
            JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (!response.isSuccessful() && !json.has("error")) throw new IOException("Google sign-in service unavailable");
            return json;
        }
    }
    String signIn(Prompt prompt) throws Exception {
        if (!configured()) throw new IllegalStateException("Google sign-in is not configured in this LiTube build.");
        JsonObject code = post("device/code", form().add("scope", "https://www.googleapis.com/auth/youtube.readonly").build());
        if (code.has("error")) throw new IOException("Google rejected this client configuration. Check the LiTube TV OAuth registration.");
        String verify = code.get("verification_url").getAsString();
        HttpUrl url = HttpUrl.get(verify);
        if (!url.isHttps() || !(url.host().equals("google.com") || url.host().endsWith(".google.com"))) throw new IOException("Invalid Google verification address");
        prompt.show(code.get("user_code").getAsString(), verify);
        long interval = Math.max(5, code.has("interval") ? code.get("interval").getAsLong() : 5);
        long deadline = SystemClock.elapsedRealtime() + Math.min(1800, code.get("expires_in").getAsLong()) * 1000;
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(interval * 1000);
            JsonObject token = post("token", form().add("client_secret", BuildConfig.TV_GOOGLE_CLIENT_SECRET)
                .add("device_code", code.get("device_code").getAsString()).add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build());
            if (token.has("error")) {
                String error = token.get("error").getAsString();
                if (error.equals("authorization_pending")) continue;
                if (error.equals("slow_down")) { interval += 5; continue; }
                if (error.equals("access_denied")) throw new IOException("Sign-in was declined. Select Sign in to try again.");
                if (error.equals("expired_token")) break;
                throw new IOException("Google could not complete sign-in. Check the app registration and try again.");
            }
            token.addProperty("expires_at", System.currentTimeMillis() + token.get("expires_in").getAsLong() * 1000);
            store.save(token);
            return "YouTube account connected";
        }
        throw new IOException("Sign-in code expired. Select Sign in for a new code.");
    }
    boolean signedIn() throws Exception { return store.load() != null; }
    String channelName() throws Exception {
        JsonObject token = store.load(); if (token == null) return "Not signed in";
        if (token.get("expires_at").getAsLong() < System.currentTimeMillis() + 60000) {
            if (!token.has("refresh_token")) throw new IOException("Please sign in again");
            JsonObject refreshed = post("token", form().add("client_secret", BuildConfig.TV_GOOGLE_CLIENT_SECRET).add("refresh_token", token.get("refresh_token").getAsString()).add("grant_type", "refresh_token").build());
            if (refreshed.has("error")) throw new IOException("Please sign in again");
            refreshed.addProperty("refresh_token", token.get("refresh_token").getAsString());
            refreshed.addProperty("expires_at", System.currentTimeMillis() + refreshed.get("expires_in").getAsLong() * 1000); token = refreshed; store.save(token);
        }
        Request request = new Request.Builder().url("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
            .header("Authorization", "Bearer " + token.get("access_token").getAsString()).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) return "YouTube account connected";
            var items = JsonParser.parseString(response.body().string()).getAsJsonObject().getAsJsonArray("items");
            return items == null || items.isEmpty() ? "YouTube account connected" : "Signed in: " + items.get(0).getAsJsonObject().getAsJsonObject("snippet").get("title").getAsString();
        }
    }
    String signOut() throws Exception {
        JsonObject token = store.load(); store.clear();
        if (token != null) {
            String revoke = token.has("refresh_token") ? token.get("refresh_token").getAsString() : token.get("access_token").getAsString();
            try (Response response = http.newCall(new Request.Builder().url("https://oauth2.googleapis.com/revoke").post(new FormBody.Builder().add("token", revoke).build()).build()).execute()) {
                if (!response.isSuccessful()) return "Signed out on this TV. You can also remove LiTube access in your Google account.";
            } catch (IOException e) { return "Signed out on this TV. Remove LiTube access in your Google account when online."; }
        }
        return "Signed out";
    }
    @Override public void close() { http.dispatcher().cancelAll(); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdown(); }
}
