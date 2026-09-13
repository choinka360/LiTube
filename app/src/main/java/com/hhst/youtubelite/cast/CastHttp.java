package com.hhst.youtubelite.cast;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One bounded HTTP request per TLS connection; no proxying, streaming bodies or keep-alive. */
public final class CastHttp {
    public record Request(String path, String token, byte[] body) {}
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        for (int i = 0; i < 4096; i++) {
            int b = input.read();
            if (b == -1) throw new EOFException();
            if (previous == '\r' && b == '\n') return out.toString(StandardCharsets.US_ASCII.name()).substring(0, out.size() - 1);
            out.write(b); previous = b;
        }
        throw new IOException("Header too large");
    }
    public static Request read(InputStream input) throws IOException {
        String[] first = line(input).split(" ");
        if (first.length != 3 || !first[0].equals("POST") || !first[2].equals("HTTP/1.1") || !first[1].matches("/[a-z-]+")) throw new IOException("Invalid request");
        Map<String, String> headers = new HashMap<>();
        int size = 0;
        for (int count = 0; ; count++) {
            String value = line(input); size += value.length();
            if (size > 8192 || count > 32) throw new IOException("Headers too large");
            if (value.isEmpty()) break;
            int colon = value.indexOf(':');
            if (colon < 1) throw new IOException("Invalid header");
            String key = value.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, value.substring(colon + 1).trim()) != null) throw new IOException("Duplicate header");
        }
        if (headers.containsKey("transfer-encoding")) throw new IOException("Streaming bodies unsupported");
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
        catch (NumberFormatException e) { throw new IOException("Invalid length"); }
        if (length < 0 || length > 4096) throw new IOException("Body too large");
        byte[] body = new byte[length];
        new DataInputStream(input).readFully(body);
        return new Request(first[1], headers.getOrDefault("authorization", "").replaceFirst("^Bearer ", ""), body);
    }
    public static void respond(OutputStream out, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }
}
