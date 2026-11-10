package com.hhst.youtubelite.cast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed UDP commands for casting from outside the home network, in the same format as the
 * RemoteWakeUp TV helper: {@code NAME:<unix ms>[:arg]...:<hex HMAC-SHA256 of everything before it>}.
 * The router forwards them to the TV, which answers through the same path.
 */
public final class RemoteCommand {
    /** LiTube's UDP port on the TV; the router forwards its outside port here. The helper uses 9009. */
    public static final int TV_PORT = 9010;
    /** The RemoteWakeUp helper's port on the TV, for direct delivery on the home network. */
    public static final int HELPER_TV_PORT = 9009;
    public static final int MAX_PACKET = 256;
    static final long MAX_CLOCK_SKEW_MS = 120_000;
    private static final Pattern FORMAT = Pattern.compile("^([A-Z]{4,10}):(\\d{1,15})((?::[A-Za-z0-9_-]{1,16}){0,2}):([0-9a-f]{64})$");

    public record Parsed(String name, long timestamp, List<String> args) {}

    private RemoteCommand() {}

    public static String sign(String secret, String name, long timestamp, String... args) {
        StringBuilder text = new StringBuilder(name).append(':').append(timestamp);
        for (String arg : args) text.append(':').append(arg);
        return text + ":" + hmac(secret, text.toString());
    }

    /** Returns the command, or null if it is malformed or not signed with this secret. */
    public static Parsed verify(String secret, String packet) {
        if (secret == null || secret.isEmpty() || packet == null || packet.length() > MAX_PACKET) return null;
        Matcher m = FORMAT.matcher(packet.trim());
        if (!m.matches()) return null;
        String signed = m.group(1) + ":" + m.group(2) + m.group(3);
        byte[] expected = hmac(secret, signed).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, m.group(4).getBytes(StandardCharsets.US_ASCII))) return null;
        List<String> args = new ArrayList<>();
        if (!m.group(3).isEmpty()) args.addAll(Arrays.asList(m.group(3).substring(1).split(":")));
        return new Parsed(m.group(1), Long.parseLong(m.group(2)), args);
    }

    /**
     * The TV's clock starts in 2023 after every boot and corrects itself later, so freshness is
     * only checked once the clock looks right, as the TV helper does.
     */
    public static boolean fresh(long timestamp, long now) {
        Calendar calendar = Calendar.getInstance(Locale.ROOT);
        calendar.setTimeInMillis(now);
        return calendar.get(Calendar.YEAR) < 2026 || Math.abs(now - timestamp) <= MAX_CLOCK_SKEW_MS;
    }

    static String hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(text.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Replay protection for commands that change something. Each must be newer than the last;
     * a repeated copy of the last one (UDP is sent several times, by two routes) gets the same
     * reply again instead of being run twice.
     */
    static final class Guard {
        enum Verdict { RUN, REPEAT, IGNORE }
        private long lastTimestamp;
        private String lastReply;
        Guard(long lastTimestamp) { this.lastTimestamp = lastTimestamp; }
        synchronized Verdict check(long timestamp) {
            if (timestamp > lastTimestamp) return Verdict.RUN;
            return timestamp == lastTimestamp && lastReply != null ? Verdict.REPEAT : Verdict.IGNORE;
        }
        synchronized void accepted(long timestamp) { lastTimestamp = timestamp; lastReply = null; }
        synchronized void replied(long timestamp, String reply) { if (timestamp == lastTimestamp) lastReply = reply; }
        synchronized String lastReply() { return lastReply; }
        synchronized long lastTimestamp() { return lastTimestamp; }
    }
}
