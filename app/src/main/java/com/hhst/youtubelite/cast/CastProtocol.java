package com.hhst.youtubelite.cast;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

public final class CastProtocol {
    public static final int PORT = 8642;
    public static final String SERVICE_TYPE = "_litube._tcp.";
    private CastProtocol() {}
    public static String videoId(String input) {
        if (input == null || input.length() > 2048) throw new IllegalArgumentException("Enter a YouTube link or video ID");
        input = input.trim();
        if (input.matches("[A-Za-z0-9_-]{11}")) return input;
        try {
            URI uri = URI.create(input);
            String host = uri.getHost();
            if (host == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            String id = null;
            if (host.equals("youtu.be")) id = uri.getPath().substring(1);
            else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")) {
                if (uri.getPath().equals("/watch") && uri.getRawQuery() != null) {
                    for (String part : uri.getRawQuery().split("&")) if (part.startsWith("v=")) id = URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8.name());
                } else if (uri.getPath().matches("/(shorts|live|embed)/[A-Za-z0-9_-]{11}")) id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            }
            if (id != null && id.matches("[A-Za-z0-9_-]{11}")) return id;
        } catch (Exception ignored) {}
        throw new IllegalArgumentException("Use a valid YouTube video link");
    }
    public static long position(long value) {
        if (value < 0 || value > 7L * 24 * 60 * 60 * 1000) throw new IllegalArgumentException("Invalid playback position");
        return value;
    }
    public static String randomToken() { byte[] data = new byte[32]; new SecureRandom().nextBytes(data); return Base64.getUrlEncoder().withoutPadding().encodeToString(data); }
    public static String hash(byte[] data) {
        try { StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) result.append(String.format(Locale.ROOT, "%02X", b & 255)); return result.toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String normalizeCode(String code) {
        String normalized = code.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-F0-9]{16}|[A-F0-9]{64}")) throw new IllegalArgumentException("Enter the 16-character TV code");
        return normalized;
    }
}
