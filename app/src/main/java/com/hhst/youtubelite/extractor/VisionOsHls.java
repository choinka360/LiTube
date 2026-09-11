package com.hhst.youtubelite.extractor;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.*;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;

/** VisionOS HLS request adapted from upstream NewPipe's YoutubeStreamHelper. */
public final class VisionOsHls {
    public static final String USER_AGENT = "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)";
    private final DownloaderImpl downloader;

    VisionOsHls(DownloaderImpl downloader) { this.downloader = downloader; }

    @Nullable String fetch(String videoId, ExtractionSession parent) {
        ExtractionSession session = new ExtractionSession();
        parent.register(session::cancel);
        try {
            return downloader.withExtractionSession(() -> {
                InnertubeClientRequestInfo client = InnertubeClientRequestInfo.ofIosClient();
                client.clientInfo.clientName = "VISIONOS";
                client.clientInfo.clientVersion = "1.04";
                client.clientInfo.clientId = "101";
                client.deviceInfo.deviceModel = "RealityDevice17,1";
                client.deviceInfo.osName = "visionOS";
                client.deviceInfo.osVersion = "26.6.0.23O770";
                Map<String, List<String>> headers = Map.of("User-Agent", List.of(USER_AGENT),
                        "X-Goog-Api-Format-Version", List.of("2"));
                client.clientInfo.visitorData = getVisitorDataFromInnertube(client,
                        Localization.DEFAULT, ContentCountry.DEFAULT, headers, YOUTUBEI_V1_URL, null, false);
                String cpn = generateContentPlaybackNonce();
                Map<String, Object> clientJson = new java.util.LinkedHashMap<>();
                clientJson.put("clientName", client.clientInfo.clientName);
                clientJson.put("clientVersion", client.clientInfo.clientVersion);
                clientJson.put("visitorData", client.clientInfo.visitorData);
                clientJson.put("deviceMake", "Apple");
                clientJson.put("deviceModel", client.deviceInfo.deviceModel);
                clientJson.put("osName", client.deviceInfo.osName);
                clientJson.put("osVersion", client.deviceInfo.osVersion);
                clientJson.put("platform", "MOBILE");
                clientJson.put("hl", "en");
                clientJson.put("gl", "US");
                byte[] body = new Gson().toJson(Map.of("context", Map.of("client", clientJson),
                        "videoId", videoId, "cpn", cpn, "contentCheckOk", true, "racyCheckOk", true))
                        .getBytes(StandardCharsets.UTF_8);
                String url = YOUTUBEI_V1_GAPIS_URL + "player?prettyPrint=false&t=" + generateTParameter() + "&id=" + videoId;
                var response = downloader.postWithContentTypeJson(url, headers, body, Localization.DEFAULT);
                try {
                    JsonObject json = JsonParser.parseString(response.responseBody()).getAsJsonObject();
                    JsonObject data = json.getAsJsonObject("streamingData");
                    String manifest = data != null && data.has("hlsManifestUrl") ? data.get("hlsManifestUrl").getAsString() : null;
                    Log.d("YTLPlayback", "VisionOS HLS available=" + (manifest != null));
                    return manifest != null && manifest.startsWith("https://") ? manifest : null;
                } catch (Exception parseError) {
                    return null;
                }
            }, session);
        } catch (Exception failure) {
            Log.w("YTLPlayback", "VisionOS HLS extraction failed: " + failure.getClass().getSimpleName());
            return null;
        }
    }
}