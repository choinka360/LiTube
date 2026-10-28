package com.hhst.youtubelite.cast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class CastProtocolTest {
    @Test public void acceptsYoutubeLinksAndIds() {
        for (String value : new String[]{"WqbreABNlX4", "https://youtu.be/WqbreABNlX4?si=test", "https://www.youtube.com/watch?v=WqbreABNlX4&t=12", "https://m.youtube.com/shorts/WqbreABNlX4", "https://youtube.com/live/WqbreABNlX4"}) assertEquals("WqbreABNlX4", CastProtocol.videoId(value));
    }
    @Test public void refusesArbitraryNetworkUrlsAndMalformedIds() {
        for (String value : new String[]{"http://192.168.1.1/watch?v=WqbreABNlX4", "https://youtube.com.evil.test/watch?v=WqbreABNlX4", "file:///watch?v=WqbreABNlX4", "https://youtu.be/WqbreABNlX4extra", "https://www.youtube.com/playlist?list=abc"}) assertThrows(IllegalArgumentException.class, () -> CastProtocol.videoId(value));
    }
    @Test public void rejectsNegativeOrExcessivePosition() {
        assertEquals(12345, CastProtocol.position(12345));
        assertThrows(IllegalArgumentException.class, () -> CastProtocol.position(-1));
        assertThrows(IllegalArgumentException.class, () -> CastProtocol.position(Long.MAX_VALUE));
    }
    @Test public void acceptsOnlyPhysicalPairingCodeOrFullPin() {
        assertEquals("0123456789ABCDEF", CastProtocol.normalizeCode("0123-4567-89ab-cdef"));
        assertThrows(IllegalArgumentException.class, () -> CastProtocol.normalizeCode("123456"));
    }
    @Test public void authenticationTokensAreUnpredictableAndFixedLength() {
        String first = CastProtocol.randomToken(), second = CastProtocol.randomToken();
        assertEquals(43, first.length()); assertNotEquals(first, second); assertEquals(64, CastProtocol.hash(first).length());
    }
    @Test public void wakePacketContainsCorrectRepeatedMac() {
        byte[] packet = WakeOnLan.packet("00:11:22:AA:BB:CC"); assertEquals(102, packet.length);
        for (int i = 0; i < 6; i++) assertEquals((byte)255, packet[i]);
        for (int i = 0; i < 16; i++) assertArrayEquals(new byte[]{0,17,34,(byte)170,(byte)187,(byte)204}, Arrays.copyOfRange(packet, 6+i*6,12+i*6));
        assertThrows(IllegalArgumentException.class, () -> WakeOnLan.packet("not-a-mac"));
    }
    private CastHttp.Request read(String request) throws IOException { return CastHttp.read(new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII))); }
    @Test public void parsesBoundedAuthenticatedRequest() throws Exception {
        var request = read("POST /pause HTTP/1.1\r\nAuthorization: Bearer secret\r\nContent-Length: 2\r\n\r\n{}");
        assertEquals("/pause", request.path()); assertEquals("secret", request.token()); assertEquals("{}", new String(request.body(), StandardCharsets.UTF_8));
    }
    @Test public void rejectsRequestSmugglingAndOversizedBodies() {
        for (String headers : new String[]{"Content-Length: 2\r\nContent-Length: 3\r\n", "Content-Length: 2\r\nTransfer-Encoding: chunked\r\n", "Content-Length: 5000\r\n", "Content-Length: -1\r\n"}) assertThrows(IOException.class, () -> read("POST /play HTTP/1.1\r\n" + headers + "\r\n{}"));
    }
    @Test public void pairingCodeMatchesOnBothScreensAndDependsOnRequest() {
        String fingerprint = CastProtocol.hash("tv certificate");
        String nonce = CastProtocol.randomToken();
        String code = CastProtocol.verificationCode(fingerprint, nonce);
        assertTrue(code.matches("[0-9]{4}"));
        assertEquals(code, CastProtocol.verificationCode(fingerprint.toLowerCase(java.util.Locale.ROOT), nonce));
        boolean differs = false;
        for (int i = 0; i < 8 && !differs; i++) differs = !code.equals(CastProtocol.verificationCode(fingerprint, CastProtocol.randomToken()));
        assertTrue("Code must change with each pairing request", differs);
    }
    @Test public void advertisedIdMatchesPairingCodePrefix() {
        String fingerprint = CastProtocol.hash("tv certificate");
        assertEquals(16, CastProtocol.deviceId(fingerprint).length());
        assertEquals(CastProtocol.normalizeCode(fingerprint.substring(0, 16)), CastProtocol.deviceId(fingerprint));
    }
    @Test public void rememberedTvIsRecognisedByIdentityNotAddress() {
        CastClient.Device saved = new CastClient.Device(); saved.host = "192.168.1.12"; saved.pin = CastProtocol.hash("tv"); saved.token = CastProtocol.randomToken();
        CastClient.Device moved = new CastClient.Device(); moved.host = "192.168.1.40"; moved.pin = saved.pin.substring(0, 16);
        CastClient.Device other = new CastClient.Device(); other.host = "192.168.1.12"; other.pin = CastProtocol.hash("other tv");
        assertTrue(saved.paired()); assertTrue(saved.sameTv(moved)); assertFalse(saved.sameTv(other));
    }
    @Test public void tvCardFormatting() {
        assertEquals("4:05", TvVideo.clock(245)); assertEquals("1:02:03", TvVideo.clock(3723));
        assertEquals("999", TvVideo.count(999)); assertEquals("1.2K", TvVideo.count(1234)); assertEquals("15K", TvVideo.count(15_900)); assertEquals("3M", TvVideo.count(3_000_000));
    }
    @Test public void rejectsTruncationUnsupportedMethodsAndProxyTargets() {
        for (String request : new String[]{"POST /play HTTP/1.1\r\nContent-Length: 3\r\n\r\n{}", "GET /play HTTP/1.1\r\n\r\n", "POST https://example.com HTTP/1.1\r\n\r\n"}) assertThrows(IOException.class, () -> read(request));
    }
}
