package com.hhst.youtubelite.cast;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import com.google.gson.JsonObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Receives signed casting commands from outside the home network (router port → TV UDP 9010).
 * Only play, pause, resume, seek, stop, volume and status are accepted; pairing never is.
 * Unsigned or invalid packets get no reply.
 */
final class RemoteCastListener implements AutoCloseable {
    interface Handler { JsonObject handle(String path, JsonObject body) throws Exception; }
    private static final String TAG = "LiTubeCast";
    private static final Set<String> TRIMMED = Set.of("videoId", "title", "author", "name", "positionMs", "durationMs",
            "playing", "playWhenReady", "ended", "loading", "speed", "version");
    private final String secret;
    private final Handler handler;
    private final SharedPreferences prefs;
    private final RemoteCommand.Guard guard;
    private final Thread thread;
    private volatile boolean closed;
    private volatile DatagramSocket socket;

    RemoteCastListener(String secret, SharedPreferences prefs, Handler handler) {
        this.secret = secret; this.handler = handler; this.prefs = prefs;
        guard = new RemoteCommand.Guard(prefs.getLong("remote_last_ts", 0));
        thread = new Thread(this::listen, "LiTube-remote");
        thread.start();
    }

    private void listen() {
        while (!closed) {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(RemoteCommand.TV_PORT));
                socket = s;
                Log.i(TAG, "Remote casting listening on UDP " + RemoteCommand.TV_PORT);
                byte[] buffer = new byte[RemoteCommand.MAX_PACKET + 1];
                while (!closed) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    s.receive(packet);
                    if (packet.getLength() > RemoteCommand.MAX_PACKET) continue;
                    receive(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII), packet.getSocketAddress());
                }
            } catch (Exception e) {
                // The socket can break across standby and network changes; recreate it.
                if (closed) return;
                Log.w(TAG, "Remote casting socket error, restarting: " + e);
                SystemClock.sleep(5000);
            }
        }
    }

    private void receive(String text, SocketAddress sender) {
        RemoteCommand.Parsed command = RemoteCommand.verify(secret, text);
        if (command == null || !RemoteCommand.fresh(command.timestamp(), System.currentTimeMillis())) return;
        String path = path(command.name());
        if (path == null) return;
        // A status question changes nothing, so it is neither counted nor checked for replays.
        if (!path.equals("/status")) {
            switch (guard.check(command.timestamp())) {
                case IGNORE -> { return; }
                case REPEAT -> { reply(sender, guard.lastReply()); return; }
                case RUN -> {
                    guard.accepted(command.timestamp());
                    prefs.edit().putLong("remote_last_ts", command.timestamp()).apply();
                }
            }
            Log.i(TAG, "Remote " + command.name() + " from " + sender);
        }
        JsonObject result;
        try { result = trim(handler.handle(path, body(command))); }
        catch (Exception e) { result = CastClient.body("error", e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : "TV could not complete the request"); }
        String answer = result.toString();
        if (!path.equals("/status")) guard.replied(command.timestamp(), answer);
        reply(sender, answer);
    }

    private static String path(String name) {
        return switch (name) {
            case "LTPLAY" -> "/play";
            case "LTPAUSE" -> "/pause";
            case "LTRESUME" -> "/resume";
            case "LTSEEK" -> "/seek";
            case "LTSTOP" -> "/stop";
            case "LTVOL" -> "/volume";
            case "LTSTATUS" -> "/status";
            default -> null;
        };
    }

    private static JsonObject body(RemoteCommand.Parsed command) {
        JsonObject body = new JsonObject();
        var args = command.args();
        switch (command.name()) {
            case "LTPLAY", "LTSEEK" -> {
                if (args.size() != 2) throw new IllegalArgumentException("Invalid command");
                body.addProperty("videoId", args.get(0));
                body.addProperty("positionMs", Long.parseLong(args.get(1)));
                // Retries of one command carry the same timestamp, so playback starts once.
                if (command.name().equals("LTPLAY")) body.addProperty("requestId", "remote-" + command.timestamp());
            }
            case "LTPAUSE", "LTRESUME" -> { if (args.size() == 1) body.addProperty("videoId", args.get(0)); }
            case "LTVOL" -> { if (args.size() == 1) body.addProperty("delta", Integer.parseInt(args.get(0))); }
            default -> { }
        }
        return body;
    }

    /** A few hundred bytes, so a reply is never fragmented on its way back through the router. */
    private static JsonObject trim(JsonObject full) {
        JsonObject result = new JsonObject();
        for (String key : TRIMMED) if (full.has(key)) result.add(key, full.get(key));
        return result;
    }

    private void reply(SocketAddress sender, String message) {
        DatagramSocket s = socket;
        if (s == null || message == null) return;
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(data, data.length, sender));
        } catch (Exception e) {
            Log.d(TAG, "Remote reply failed: " + e);
        }
    }

    @Override public void close() {
        closed = true;
        DatagramSocket s = socket;
        if (s != null) s.close();
        thread.interrupt();
    }
}
