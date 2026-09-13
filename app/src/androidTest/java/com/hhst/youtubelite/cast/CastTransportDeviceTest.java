package com.hhst.youtubelite.cast;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import com.google.gson.JsonObject;

public class CastTransportDeviceTest {
    @Test public void encryptedLocalCommandsAndWrongCertificateRejection() throws Exception {
        CastTls.Identity identity = CastTls.identity();
        String token = CastProtocol.randomToken();
        try (CastServer server = new CastServer(identity, 0, (path, auth, body) -> {
            if (!token.equals(auth)) throw new SecurityException();
            JsonObject result = CastClient.body("title", "Test TV"); result.addProperty("positionMs", body.get("positionMs").getAsLong()); return result;
        })) {
            CastClient.Device device = new CastClient.Device(); device.host = "127.0.0.1"; device.port = server.port(); device.pin = identity.fingerprint(); device.token = token;
            JsonObject body = new JsonObject(); body.addProperty("positionMs", 45000);
            try (CastClient client = new CastClient(device)) { assertEquals(45000, client.request("play", body).get("positionMs").getAsLong()); }
            device.token = "unauthorized";
            try (CastClient client = new CastClient(device)) { assertThrows(IllegalArgumentException.class, () -> client.request("pause", body)); }
            device.token = token; device.pin = "0000000000000000";
            try (CastClient client = new CastClient(device)) { assertThrows(javax.net.ssl.SSLException.class, () -> client.request("play", body)); }
        }
    }
    @Test public void refusesPublicEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> CastClient.validateHost("8.8.8.8"));
    }
}
