package com.hhst.youtubelite.cast;

import android.os.SystemClock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hhst.youtubelite.BuildConfig;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Casting from outside the home network, the RemoteWakeUp way: signed UDP commands go to the
 * router (which forwards them to the TV) and to the TV's home address. Replies come back the
 * same way. Waking uses the RemoteWakeUp TV helper's TVWAKE command.
 */
final class RemoteLink {
    private static final AtomicLong lastTimestamp = new AtomicLong();
    /** No reply arrived through the router or the home network. */
    static final class NoAnswer extends java.io.IOException { NoAnswer(String message) { super(message); } }
    private final String lanHost;

    RemoteLink(String lanHost) { this.lanHost = lanHost; }

    /** This build has the router address and key for casting from outside. */
    static boolean configured() { return !BuildConfig.REMOTE_SECRET.isBlank() && !BuildConfig.REMOTE_HOST.isBlank(); }

    /** Each command must be newer than the last one the TV accepted, even within one millisecond. */
    private static long timestamp() {
        return lastTimestamp.updateAndGet(last -> Math.max(System.currentTimeMillis(), last + 1));
    }

    /**
     * Sends a command and waits for the TV's status reply, resending the same packet while none
     * arrives. Paths and bodies match the home-network protocol ({@link CastClient#request}).
     */
    JsonObject request(String path, JsonObject body, long patienceMs) throws Exception {
        if (!configured()) throw new IllegalStateException("Casting from outside is not set up in this build");
        String videoId = body.has("videoId") ? body.get("videoId").getAsString() : null;
        long ts = timestamp();
        String secret = BuildConfig.REMOTE_SECRET;
        String packet = switch (path) {
            case "play" -> RemoteCommand.sign(secret, "LTPLAY", ts, videoId, Long.toString(body.get("positionMs").getAsLong()));
            case "seek" -> RemoteCommand.sign(secret, "LTSEEK", ts, videoId, Long.toString(body.get("positionMs").getAsLong()));
            case "pause" -> videoId == null ? RemoteCommand.sign(secret, "LTPAUSE", ts) : RemoteCommand.sign(secret, "LTPAUSE", ts, videoId);
            case "resume" -> videoId == null ? RemoteCommand.sign(secret, "LTRESUME", ts) : RemoteCommand.sign(secret, "LTRESUME", ts, videoId);
            case "volume" -> RemoteCommand.sign(secret, "LTVOL", ts, Integer.toString(body.get("delta").getAsInt()));
            case "stop" -> RemoteCommand.sign(secret, "LTSTOP", ts);
            case "status" -> RemoteCommand.sign(secret, "LTSTATUS", ts);
            default -> throw new IllegalArgumentException("Not available away from home");
        };
        String reply = exchange(packet, targets(BuildConfig.REMOTE_PORT, RemoteCommand.TV_PORT), patienceMs, true);
        if (reply == null) throw new NoAnswer("The TV didn't answer through the internet. Check that it has power and that the router forwards UDP " + BuildConfig.REMOTE_PORT + " to it.");
        JsonObject result = JsonParser.parseString(reply).getAsJsonObject();
        if (result.has("error")) throw new IllegalArgumentException(result.get("error").getAsString());
        return result;
    }

    /**
     * Asks the RemoteWakeUp TV helper to switch the TV on. Signed once per call: every newly
     * signed TVWAKE restarts the helper's work, which would cut short its watch over a TV that
     * has just started. Returns the helper's reply (DONE, WAIT or FAILED ...), or null.
     */
    String wakeTv(long patienceMs) {
        if (BuildConfig.TV_HELPER_SECRET.isBlank() || BuildConfig.REMOTE_HOST.isBlank()) return null;
        String packet = RemoteCommand.sign(BuildConfig.TV_HELPER_SECRET, "TVWAKE", System.currentTimeMillis());
        try { return exchange(packet, targets(BuildConfig.TV_HELPER_PORT, RemoteCommand.HELPER_TV_PORT), patienceMs, false); }
        catch (Exception e) { return null; }
    }

    private List<InetSocketAddress> targets(int routerPort, int tvPort) {
        List<InetSocketAddress> targets = new ArrayList<>();
        try { targets.add(new InetSocketAddress(InetAddress.getByName(BuildConfig.REMOTE_HOST), routerPort)); }
        catch (Exception ignored) { } // no network yet or unknown name; the home address may still work
        try {
            InetAddress tv = InetAddress.getByName(lanHost);
            if (tv.isSiteLocalAddress()) targets.add(new InetSocketAddress(tv, tvPort));
        } catch (Exception ignored) { }
        return targets;
    }

    /** UDP can drop packets, so each is sent three times to every target, and again every 2 seconds. */
    private static String exchange(String packet, List<InetSocketAddress> targets, long patienceMs, boolean resend) throws Exception {
        if (targets.isEmpty()) throw new NoAnswer("No internet connection");
        byte[] data = packet.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket socket = new DatagramSocket()) {
            long end = SystemClock.elapsedRealtime() + patienceMs, nextSend = 0;
            byte[] buffer = new byte[1500];
            while (true) {
                long now = SystemClock.elapsedRealtime();
                if (now >= end) return null;
                if (now >= nextSend && (resend || nextSend == 0)) {
                    for (InetSocketAddress target : targets) {
                        for (int i = 0; i < 3; i++) {
                            try { socket.send(new DatagramPacket(data, data.length, target)); } catch (Exception ignored) { }
                        }
                    }
                    nextSend = now + 2000;
                }
                socket.setSoTimeout((int) Math.max(1, Math.min(end, resend ? nextSend : end) - now));
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(reply); }
                catch (SocketTimeoutException e) { continue; }
                return new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
            }
        }
    }
}
