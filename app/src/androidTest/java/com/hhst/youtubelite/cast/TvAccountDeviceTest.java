package com.hhst.youtubelite.cast;

import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TvAccountDeviceTest {
    @Test public void tokensAreEncryptedAndCanBeRemoved() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var store = new TvAccountStore(context, "litube_account_test");
        try {
            JsonObject token = CastClient.body("access_token", "test-access-token"); token.addProperty("refresh_token", "test-refresh-token");
            store.save(token);
            assertEquals(token, store.load());
            String encrypted = context.getSharedPreferences("litube_account_test", 0).getString("encrypted", "");
            assertFalse(encrypted.contains("test-access-token")); assertFalse(encrypted.contains("test-refresh-token"));
            store.clear(); assertNull(store.load());
        } finally { store.clear(); }
    }
    @Test public void youtubeReturnsSuggestedVideos() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.startActivitySync(new android.content.Intent(instrumentation.getTargetContext(), TvActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        var suggestions = TvSuggestions.load("WqbreABNlX4");
        assertFalse("YouTube returned no related videos", suggestions.isEmpty());
        assertTrue(suggestions.size() <= 20);
        java.util.Set<String> urls = new java.util.HashSet<>();
        for (var item : suggestions) { assertTrue(urls.add(item.getUrl())); assertFalse(item.getName().isEmpty()); }
        android.util.Log.i("LiTubeCastTest", "Suggested video count=" + suggestions.size());
    }
}
