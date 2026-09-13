package com.hhst.youtubelite.cast;

import java.net.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;

public final class CastServer implements AutoCloseable {
    public interface Handler { JsonObject handle(String path, String token, JsonObject body) throws Exception; }
    private final SSLServerSocket server;
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    public CastServer(CastTls.Identity identity, int port, Handler handler) throws Exception {
        server = (SSLServerSocket) identity.context().getServerSocketFactory().createServerSocket();
        server.setReuseAddress(true); server.bind(new InetSocketAddress(port));
        acceptor.execute(() -> {
            while (!server.isClosed()) {
                try {
                    SSLSocket socket = (SSLSocket) server.accept(); socket.setSoTimeout(5000);
                    try { workers.execute(() -> serve(socket, handler)); }
                    catch (RejectedExecutionException busy) { socket.close(); }
                } catch (Exception e) { if (!server.isClosed()) close(); }
            }
        });
    }
    private void serve(SSLSocket socket, Handler handler) {
        try (socket) {
            CastHttp.Request request = CastHttp.read(socket.getInputStream());
            try {
                JsonObject body = request.body().length == 0 ? new JsonObject() : JsonParser.parseString(new String(request.body(), StandardCharsets.UTF_8)).getAsJsonObject();
                CastHttp.respond(socket.getOutputStream(), 200, handler.handle(request.path(), request.token(), body).toString());
            } catch (Exception invalid) {
                JsonObject error = new JsonObject();
                error.addProperty("error", invalid instanceof SecurityException ? "Pair this phone with the TV first" : invalid instanceof IllegalArgumentException ? invalid.getMessage() : "TV could not complete the request");
                CastHttp.respond(socket.getOutputStream(), invalid instanceof SecurityException ? 401 : 400, error.toString());
            }
        } catch (Exception ignored) { android.util.Log.w("LiTubeCast", "Connection closed: " + ignored.getClass().getSimpleName() + ": " + ignored.getMessage()); }
    }
    public int port() { return server.getLocalPort(); }
    @Override public void close() { try { server.close(); } catch (Exception ignored) {} acceptor.shutdownNow(); workers.shutdownNow(); }
}
