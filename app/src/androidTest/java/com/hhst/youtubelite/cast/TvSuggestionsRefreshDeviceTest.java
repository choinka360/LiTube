package com.hhst.youtubelite.cast;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.action.ViewActions.click;

/** Exercises the actual Refresh button without replacing the user's installed application. */
public class TvSuggestionsRefreshDeviceTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Object field(TvActivity activity, String name) throws Exception {
        var field = TvActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private Button findButton(View view, String text) {
        if (view instanceof Button button && button.getText().toString().equals(text)) return button;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button found = findButton(group.getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private void awaitResults(TvActivity activity) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 90000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                try { ready[0] = ((Button) field(activity, "refreshSuggestions")).isEnabled(); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            if (ready[0]) {
                instrumentation.runOnMainSync(() -> {
                    try {
                        assertTrue(((TextView) field(activity, "suggestionsStatus")).getText().toString().startsWith("Suggested by YouTube"));
                        assertTrue(((LinearLayout) field(activity, "suggestions")).getChildCount() > 0);
                    } catch (Exception e) { throw new AssertionError(e); }
                });
                return;
            }
            Thread.sleep(200);
        }
        fail("Refresh never completed");
    }
    @Test public void refreshExplainsMissingVideoAndReloadsRememberedVideo() throws Exception {
        var context = instrumentation.getTargetContext();
        assertTrue("Run only in the isolated validation build", context.getPackageName().endsWith(".validation"));
        var preferences = context.getSharedPreferences("litube_tv_suggestions", 0);
        preferences.edit().clear().commit();
        TvActivity activity = (TvActivity) instrumentation.startActivitySync(new Intent(context, TvActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                Button refresh = findButton(activity.getWindow().getDecorView(), "Refresh suggested videos");
                assertNotNull(refresh); assertTrue(refresh.performClick());
            });
            onView(withText("Choose a video first")).check(matches(isDisplayed()));
            onView(withText("OK")).perform(click());
            // Emulates a remembered clip from a previous process, with no current player item.
            preferences.edit().putString("last_video", "WqbreABNlX4").commit();
            for (int attempt = 0; attempt < 2; attempt++) {
                instrumentation.runOnMainSync(() -> {
                    try {
                        Button refresh = (Button) field(activity, "refreshSuggestions");
                        assertTrue(refresh.isEnabled()); refresh.performClick();
                        assertFalse("Loading must disable duplicate requests", refresh.isEnabled());
                        assertEquals("Loading YouTube suggestions\u2026", ((TextView) field(activity, "suggestionsStatus")).getText().toString());
                        assertEquals(0, ((LinearLayout) field(activity, "suggestions")).getChildCount());
                    } catch (Exception e) { throw new AssertionError(e); }
                });
                awaitResults(activity);
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    try { assertTrue("Loaded suggestions must receive TV remote focus", ((LinearLayout) field(activity, "suggestions")).getChildAt(0).hasFocus()); }
                    catch (Exception e) { throw new AssertionError(e); }
                });
            }
            try (var output = new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null), "tv-suggestions-validation.png"))) {
                instrumentation.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            context.stopService(new Intent(context, TvReceiverService.class));
            preferences.edit().clear().commit();
        }
    }
}
