package com.hhst.youtubelite.cast;

import android.os.SystemClock;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in test against a real TV. A person approves the pairing request on its screen. */
public class CastEndToEndDeviceTest {
    @Test public void phonePairsAndControlsRealTv() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        CastClient.Device device = new CastClient.Device();
        device.host = args.getString("host"); device.pin = args.getString("pin");
        assertNotNull("Provide host and pin instrumentation arguments", device.host);
        String nonce = CastProtocol.randomToken();
        try (CastClient client = new CastClient(device)) {
            JsonObject pairing = CastClient.body("nonce", nonce); pairing.addProperty("name", "LiTube phone test");
            client.request("pair", pairing);
            long deadline = SystemClock.elapsedRealtime() + 90_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                JsonObject result = client.request("pair-status", CastClient.body("nonce", nonce));
                if (result.has("token")) { device.token = result.get("token").getAsString(); device.pin = result.get("pin").getAsString(); break; }
                Thread.sleep(1000);
            }
            assertFalse("TV pairing was not approved", device.token.isEmpty());
        }
        CastClient.save(InstrumentationRegistry.getInstrumentation().getTargetContext(), device);
        try (CastClient client = new CastClient(device)) {
            JsonObject play = CastClient.body("videoId", "WqbreABNlX4"); play.addProperty("positionMs", 0); play.addProperty("requestId", CastProtocol.randomToken());
            client.request("play", play);
            JsonObject status = waitPlaying(client, 90_000);
            assertTrue("TV did not start playback: " + status, status.get("playing").getAsBoolean());
            Thread.sleep(5000);
            client.request("pause", new JsonObject());
            long paused = client.request("status", new JsonObject()).get("positionMs").getAsLong();
            Thread.sleep(1500);
            status = client.request("status", new JsonObject());
            assertFalse(status.get("playing").getAsBoolean());
            assertTrue("Pause did not hold position", Math.abs(status.get("positionMs").getAsLong() - paused) < 300);
            JsonObject seek = new JsonObject(); seek.addProperty("positionMs", 70_000); client.request("seek", seek);
            client.request("resume", new JsonObject());
            status = waitPlaying(client, 30_000);
            assertTrue(status.get("playing").getAsBoolean());
            long deadline = SystemClock.elapsedRealtime() + 100_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                status = client.request("status", new JsonObject());
                Log.i("LiTubeCastTest", "TV positionMs=" + status.get("positionMs") + " height=" + status.get("height") + " playing=" + status.get("playing"));
                if (status.get("positionMs").getAsLong() > 150_000) break;
                Thread.sleep(10_000);
            }
            assertTrue("Playback failed after seeking", status.get("positionMs").getAsLong() > 150_000);
        }
    }
    @Test public void castFromPairedPhone() throws Exception {
        CastClient.Device device = CastClient.load(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertNotNull("Pair the TV first", device);
        try (CastClient client = new CastClient(device)) {
            JsonObject play = CastClient.body("videoId", "WqbreABNlX4");
            play.addProperty("positionMs", 0); play.addProperty("requestId", CastProtocol.randomToken());
            client.request("play", play);
            assertTrue("TV did not start playback", waitPlaying(client, 90000).get("playing").getAsBoolean());
        }
    }
    @Test public void remotePlaybackControls() throws Exception {
        castFromPairedPhone();
        var device = CastClient.load(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try (CastClient client = new CastClient(device)) {
            JsonObject state = client.request("pause", new JsonObject());
            Thread.sleep(1000); state = client.request("status", new JsonObject());
            assertFalse(state.get("playing").getAsBoolean());
            long paused = state.get("positionMs").getAsLong(); Thread.sleep(1000);
            assertTrue(Math.abs(client.request("status", new JsonObject()).get("positionMs").getAsLong() - paused) < 300);
            JsonObject speed = new JsonObject(); speed.addProperty("speed", 1.5f);
            assertEquals(1.5f, client.request("speed", speed).get("speed").getAsFloat(), 0.01f);
            JsonObject seek = new JsonObject(); seek.addProperty("positionMs", 70000); client.request("seek", seek);
            Thread.sleep(1000); state = client.request("status", new JsonObject());
            assertTrue(Math.abs(state.get("positionMs").getAsLong() - 70000) < 1000);
            JsonObject quality = CastClient.body("videoId", state.get("videoId").getAsString());
            String selected = null;
            for (var item : state.getAsJsonArray("qualities")) if (item.getAsJsonObject().get("height").getAsInt() == 720) { selected = item.getAsJsonObject().get("id").getAsString(); break; }
            assertNotNull("Expected a supported 720p track", selected); quality.addProperty("track", selected);
            client.request("quality", quality);
            JsonObject sub = CastClient.body("videoId", state.get("videoId").getAsString());
            boolean hasSubtitles = !state.getAsJsonArray("subtitles").isEmpty();
            String subtitleId = hasSubtitles ? state.getAsJsonArray("subtitles").get(0).getAsJsonObject().get("id").getAsString() : "off";
            sub.addProperty("track", subtitleId); client.request("subtitles", sub);
            client.request("resume", new JsonObject());
            long deadline = SystemClock.elapsedRealtime() + 45000;
            boolean subtitleSelected = !hasSubtitles;
            do {
                Thread.sleep(1000); state = client.request("status", new JsonObject());
                subtitleSelected = !hasSubtitles;
                for (var item : state.getAsJsonArray("subtitles")) if (item.getAsJsonObject().get("id").getAsString().equals(subtitleId)) subtitleSelected = item.getAsJsonObject().get("selected").getAsBoolean();
                if (state.get("height").getAsInt() == 720 && subtitleSelected && state.get("playing").getAsBoolean()) break;
            } while (SystemClock.elapsedRealtime() < deadline);
            assertEquals("Quality switch did not reach 720p", 720, state.get("height").getAsInt());
            assertTrue("Subtitle track was not selected", subtitleSelected);
            Log.i("LiTubeCastTest", "720p/speed/seek/pause passed; source subtitle tracks=" + state.getAsJsonArray("subtitles").size());
            assertTrue(state.get("playing").getAsBoolean());
            sub.addProperty("track", "off"); assertTrue(client.request("subtitles", sub).get("subtitlesOff").getAsBoolean());
            quality.addProperty("track", "auto"); assertTrue(client.request("quality", quality).get("qualityAuto").getAsBoolean());
            speed.addProperty("speed", 1f); client.request("speed", speed);
        }
    }
    @Test public void normalPlayerSessionPauseResume() throws Exception {
        castFromPairedPhone();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var device = CastClient.load(instrumentation.getTargetContext());
        CastSession session = CastSession.get(instrumentation.getTargetContext());
        try (CastClient client = new CastClient(device)) {
            JsonObject initial = client.request("status", new JsonObject());
            instrumentation.runOnMainSync(() -> session.acknowledge(device, "play", initial));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue(session.controls("WqbreABNlX4"));
                assertFalse(session.setPlaying("z0Eps7x1Tso", false));
                assertTrue(session.setPlaying("WqbreABNlX4", false));
            });
            long end = SystemClock.elapsedRealtime() + 10000;
            JsonObject state;
            do { Thread.sleep(250); state = client.request("status", new JsonObject()); } while (state.get("playWhenReady").getAsBoolean() && SystemClock.elapsedRealtime() < end);
            assertFalse("Normal phone control did not pause TV", state.get("playWhenReady").getAsBoolean());
            instrumentation.runOnMainSync(() -> assertTrue(session.setPlaying("WqbreABNlX4", true)));
            assertTrue("Normal phone control did not resume TV", waitPlaying(client, 15000).get("playing").getAsBoolean());
        }
    }
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Test public void nativePlayerButtonsControlTv() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var intent = new android.content.Intent(context, com.hhst.youtubelite.ui.MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        var activity = instrumentation.startActivitySync(intent);
        var playerField = activity.getClass().getDeclaredField("player"); playerField.setAccessible(true);
        var lite = (com.hhst.youtubelite.player.LitePlayer) playerField.get(activity);
        var engineField = lite.getClass().getDeclaredField("engine"); engineField.setAccessible(true);
        var engine = (com.hhst.youtubelite.player.engine.Engine) engineField.get(lite);
        var extractorField = lite.getClass().getDeclaredField("extractor"); extractorField.setAccessible(true);
        var extractor = (com.hhst.youtubelite.extractor.YoutubeExtractor) extractorField.get(lite);
        var details = extractor.getInfo("https://www.youtube.com/watch?v=WqbreABNlX4", null).get(90, java.util.concurrent.TimeUnit.SECONDS);
        instrumentation.runOnMainSync(() -> engine.play(details));
        long end;
        var device = CastClient.load(context);
        try (CastClient client = new CastClient(device)) {
            JsonObject body = CastClient.body("videoId", "WqbreABNlX4"); body.addProperty("positionMs", 0); body.addProperty("requestId", CastProtocol.randomToken());
            JsonObject state = client.request("play", body);
            CastSession.get(context).acknowledge(device, "play", state);
            instrumentation.waitForIdleSync();
            assertTrue(waitPlaying(client, 90000).get("playing").getAsBoolean());
            instrumentation.runOnMainSync(() -> {
                android.view.View pause = activity.findViewById(com.hhst.youtubelite.R.id.btn_pause);
                assertNotNull(pause); assertEquals(android.view.View.VISIBLE, pause.getVisibility()); assertTrue(pause.performClick());
            });
            end = SystemClock.elapsedRealtime() + 10000;
            do { Thread.sleep(250); state = client.request("status", new JsonObject()); } while (state.get("playWhenReady").getAsBoolean() && SystemClock.elapsedRealtime() < end);
            assertFalse("Native pause button did not pause TV", state.get("playWhenReady").getAsBoolean());
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                android.view.View play = activity.findViewById(com.hhst.youtubelite.R.id.btn_play);
                assertNotNull(play); assertEquals(android.view.View.VISIBLE, play.getVisibility()); assertTrue(play.performClick());
            });
            assertTrue("Native play button did not resume TV", waitPlaying(client, 15000).get("playing").getAsBoolean());
        }
    }
    @Test public void wakePairedTv() throws Exception {
        CastClient.Device device = CastClient.load(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertNotNull("Pair the TV first", device);
        try (CastClient client = new CastClient(device)) {
            client.request("wake", new JsonObject());
            Thread.sleep(4000);
            assertFalse("TV activity did not open", client.request("status", new JsonObject()).get("needsOpen").getAsBoolean());
        }
    }
    @Test public void discoverRealTv() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var manager = context.getSystemService(android.net.nsd.NsdManager.class);
        var wifi = context.getSystemService(android.net.wifi.WifiManager.class);
        var lock = wifi.createMulticastLock("litube-test-discovery"); lock.acquire();
        var found = new java.util.concurrent.CountDownLatch(1);
        android.net.nsd.NsdManager.DiscoveryListener listener = new android.net.nsd.NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {}
            public void onServiceFound(android.net.nsd.NsdServiceInfo info) { if (info.getServiceName().startsWith("LiTube")) found.countDown(); }
            public void onServiceLost(android.net.nsd.NsdServiceInfo info) {}
            public void onDiscoveryStopped(String type) {}
            public void onStartDiscoveryFailed(String type, int code) {}
            public void onStopDiscoveryFailed(String type, int code) {}
        };
        try {
            manager.discoverServices(CastProtocol.SERVICE_TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener);
            assertTrue("TV service was not discovered over Wi-Fi", found.await(30, java.util.concurrent.TimeUnit.SECONDS));
        } finally { manager.stopServiceDiscovery(listener); lock.release(); }
    }
    private JsonObject waitPlaying(CastClient client, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout; JsonObject result;
        do { result = client.request("status", new JsonObject()); if (result.get("playing").getAsBoolean()) return result; Thread.sleep(1000); } while (SystemClock.elapsedRealtime() < end);
        return result;
    }
}

