package com.hhst.youtubelite.cast;

import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.PlayerView;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Native, remote-first TV screen. The service owns playback across activity recreation. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class TvActivity extends AppCompatActivity implements TvReceiverService.Screen {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService suggestionsWorker = Executors.newSingleThreadExecutor();
    private Future<?> suggestionsTask;
    private Button refreshSuggestions;
    private PlayerView video;
    private TextView status, pairing, suggestionsStatus, brand;
    private LinearLayout suggestions;
    private String suggestionsId;
    private long suggestionsGeneration;
    private LinearLayout results, panel, root;
    private ScrollView controlsScroll;
    private boolean fullscreen;
    private android.app.AlertDialog pairingHelp;
    private TvReceiverService service;
    private EditText query;
    private long searchGeneration;
    private boolean visible;
    private final Runnable attach = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (TvReceiverService.instance != null) {
                service = TvReceiverService.instance; service.attach(TvActivity.this); video.setPlayer(service.player());
            } else main.postDelayed(this, 300);
        }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 24, 32, 24); root.setBackground(TvTheme.background()); root.setClipChildren(false);
        brand = label("LiTube  /  TV", 27); brand.setTextColor(TvTheme.ACCENT); brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0)); root.addView(brand);
        status = label("Your next great watch starts here", 16); TvTheme.text(status, true); root.addView(status);
        video = new PlayerView(this); video.setUseController(true); video.setControllerAutoShow(true); video.setControllerShowTimeoutMs(5000); video.setFocusable(true);
        root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView scroll = new ScrollView(this); controlsScroll = scroll; panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(10), dp(10), dp(10), dp(16)); panel.setClipChildren(false); scroll.setClipChildren(true); scroll.setClipToPadding(true); scroll.setBackground(TvTheme.card(scroll, false)); scroll.setClipToOutline(true); scroll.setElevation(dp(6)); scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(250)));
        pairing = label("Enable casting, then pair your phone", 14); TvTheme.text(pairing, true); panel.addView(pairing);
        LinearLayout actions = new LinearLayout(this); panel.addView(actions);
        button(actions, "Enable casting", () -> { ContextCompat.startForegroundService(this, new Intent(this, TvReceiverService.class)); main.post(attach); });
        button(actions, "Pair a phone", () -> { if (service != null) { service.beginPairing(); pairingHelp = new android.app.AlertDialog.Builder(this).setTitle("Pair your phone")
                .setMessage("On the phone choose Cast to TV, select this TV and enter:\n\n" + service.pairingCode() + "\n\nThen approve the phone here. Pairing stays open for two minutes.").setPositiveButton("OK", null).show(); } });
        button(actions, "Full screen", this::fullscreen);
        button(actions, "Settings", this::settings);
        button(panel, "YouTube account / Sign in", () -> startActivity(new Intent(this, TvAccountActivity.class)));
        query = new EditText(this); query.setSingleLine(true); query.setTextColor(Color.WHITE); query.setHintTextColor(Color.LTGRAY); query.setHint("Search YouTube or paste a video link"); query.setTextSize(18); query.setPadding(dp(14), dp(12), dp(14), dp(12)); query.setBackground(TvTheme.card(query, false)); panel.addView(query);
        LinearLayout searchActions = new LinearLayout(this); panel.addView(searchActions);
        button(searchActions, "Search", this::search);
        button(searchActions, "Play link", () -> { try { requireService().playLocal(query.getText().toString()); video.requestFocus(); } catch (Exception e) { message(e.getMessage()); } });
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); panel.addView(results);
        suggestionsStatus = label("Suggested videos — play or cast a clip first", 20); panel.addView(suggestionsStatus);
        refreshSuggestions = button(panel, "Refresh suggested videos", () -> loadSuggestions(true));
        suggestions = new LinearLayout(this); suggestions.setOrientation(LinearLayout.VERTICAL); panel.addView(suggestions);
        setContentView(root);
        if (saved != null && saved.getBoolean("fullscreen")) showCastFullscreen();
        ContextCompat.startForegroundService(this, new Intent(this, TvReceiverService.class));
    }
    @Override public void showCastFullscreen() {
        if (pairingHelp != null) { pairingHelp.dismiss(); pairingHelp = null; }
        fullscreen();
        video.setControllerAutoShow(false);
        video.hideController();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("fullscreen", fullscreen);
        super.onSaveInstanceState(out);
    }
    private void fullscreen() {
        fullscreen = true; controlsScroll.setVisibility(android.view.View.GONE); status.setVisibility(android.view.View.GONE); brand.setVisibility(View.GONE);
        root.setPadding(0, 0, 0, 0); video.requestFocus(); video.showController();
    }
    @Override public void onBackPressed() {
        if (fullscreen) { fullscreen = false; video.setControllerAutoShow(true); controlsScroll.setVisibility(android.view.View.VISIBLE); status.setVisibility(android.view.View.VISIBLE); brand.setVisibility(View.VISIBLE); root.setPadding(32, 24, 32, 24); query.requestFocus(); loadSuggestions(); }
        else super.onBackPressed();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); TvTheme.text(view, false); view.setPadding(4, 6, 4, 6); return view; }
    private Button button(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextColor(Color.WHITE); button.setFocusable(true); button.setFocusableInTouchMode(true); button.setAllCaps(false);
        TvTheme.button(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48)); params.setMargins(dp(4), dp(5), dp(4), dp(5)); row.addView(button, params); button.setOnClickListener(v -> action.run()); return button;
    }
    private TvReceiverService requireService() { if (service == null || TvReceiverService.instance == null) throw new IllegalStateException("Enable casting first"); return service; }
    private void message(String value) { status.setText(value == null ? "Could not complete this action" : value); }
    private void search() {
        String term = query.getText().toString().trim(); if (term.isEmpty()) return;
        long request = ++searchGeneration; results.removeAllViews(); message("Searching…");
        searchWorker.execute(() -> {
            try {
                var extractor = ServiceList.YouTube.getSearchExtractor(term); extractor.fetchPage();
                var found = extractor.getInitialPage().getItems();
                runOnUiThread(() -> { if (isDestroyed() || request != searchGeneration) return; message("Search results"); int count = 0;
                    for (var item : found) if (item instanceof StreamInfoItem stream && count++ < 20) button(results, stream.getName(), () -> { try { requireService().playLocal(stream.getUrl()); fullscreen(); } catch (Exception e) { message(e.getMessage()); } });
                    if (count == 0) message("No videos found");
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed() && request == searchGeneration) message("Search failed. Try again or cast a link from your phone."); }); }
        });
    }
    private void settings() {
        String[] options = {"Wake setup for Sony TV", "Allow opening over other apps (Android 10+)", "Forget all paired phones", "Disable casting"};
        new android.app.AlertDialog.Builder(this).setTitle("TV settings").setItems(options, (d, which) -> {
            if (which == 0) new android.app.AlertDialog.Builder(this).setTitle("Wake from standby").setMessage("On your Sony KD-55XF9005 enable Remote Start in the TV network settings. Leave LiTube casting enabled. For deeper standby, enter the TV's network MAC address in the phone's casting screen. Network wake depends on the TV keeping its network adapter active; it cannot start an unplugged TV.").setPositiveButton("OK", null).show();
            if (which == 1) { try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (Exception e) { message("This TV does not expose that permission. Keep LiTube open for casting."); } }
            if (which == 2 && service != null) new android.app.AlertDialog.Builder(this).setMessage("Revoke every phone's casting access?").setPositiveButton("Forget phones", (a, b) -> service.forgetPhones()).setNegativeButton("Cancel", null).show();
            if (which == 3) { video.setPlayer(null); stopService(new Intent(this, TvReceiverService.class)); service = null; message("Casting disabled"); }
        }).show();
    }
    @Override protected void onStart() { super.onStart(); visible = true; main.post(attach); }
    @Override protected void onStop() { visible = false; main.removeCallbacks(attach); if (service != null) service.detach(this); video.setPlayer(null); super.onStop(); }
    @Override protected void onDestroy() { searchGeneration++; suggestionsGeneration++; searchWorker.shutdownNow(); suggestionsWorker.shutdownNow(); super.onDestroy(); }
    @Override public void changed() {
        if (!visible || service == null) return;
        message(service.description());
        if (!fullscreen) loadSuggestions();
        pairing.setText("TV address: " + addresses() + ":" + service.port() + "   •   Pairing code: " + service.pairingCode());
        if (service.player().isPlaying()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void loadSuggestions() { loadSuggestions(false); }
    private void loadSuggestions(boolean manual) {
        if (suggestions == null) return;
        String id = service == null ? null : service.currentVideoId();
        if (id == null) {
            try { id = CastProtocol.videoId(query.getText().toString().trim()); }
            catch (IllegalArgumentException ignored) { }
        }
        if (id == null) id = getSharedPreferences("litube_tv_suggestions", MODE_PRIVATE).getString("last_video", null);
        if (id == null) {
            suggestionsGeneration++;
            if (suggestionsTask != null) suggestionsTask.cancel(true);
            suggestionsId = null; suggestions.removeAllViews(); refreshSuggestions.setEnabled(true);
            suggestionsStatus.setText("Play or cast a video, or enter a YouTube link, then select Refresh.");
            if (manual) new android.app.AlertDialog.Builder(this).setTitle("Choose a video first")
                .setMessage("Suggestions are based on a video. Play or cast a clip, or paste a YouTube link in the search box, then select Refresh.").setPositiveButton("OK", (d, w) -> query.requestFocus()).show();
            return;
        }
        if (!manual && id.equals(suggestionsId)) return;
        suggestionsId = id; long request = ++suggestionsGeneration;
        if (suggestionsTask != null) suggestionsTask.cancel(true);
        suggestions.removeAllViews(); suggestionsStatus.setText("Loading YouTube suggestions\u2026");
        refreshSuggestions.setEnabled(false);
        final String sourceId = id;
        suggestionsTask = suggestionsWorker.submit(() -> {
            try {
                var items = TvSuggestions.load(sourceId);
                main.post(() -> {
                    if (isDestroyed() || request != suggestionsGeneration) return;
                    refreshSuggestions.setEnabled(true);
                    suggestionsStatus.setText(items.isEmpty() ? "No suggestions returned for this video. Try another clip." : "Suggested by YouTube | " + items.size() + " videos");
                    for (var item : items) { Button tile = button(suggestions, item.getName() + "\n" + item.getUploaderName(), () -> {
                        try { requireService().playLocal(item.getUrl()); showCastFullscreen(); }
                        catch (Exception e) { message(e.getMessage()); }
                    });
                        tile.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); tile.setMaxLines(3); tile.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        tile.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT; tile.getLayoutParams().height = dp(86);
                    }
                    if (manual && !fullscreen && visible) {
                        suggestions.post(() -> {
                            if (isDestroyed() || request != suggestionsGeneration || fullscreen || !visible) return;
                            View target = suggestions.getChildCount() > 0 ? suggestions.getChildAt(0) : refreshSuggestions;
                            target.requestFocus();
                            target.requestRectangleOnScreen(new android.graphics.Rect(0, 0, target.getWidth(), target.getHeight()), true);
                        });
                    }
                });
            } catch (Exception e) { main.post(() -> {
                if (!isDestroyed() && request == suggestionsGeneration) {
                    refreshSuggestions.setEnabled(true);
                    suggestionsStatus.setText("Suggestions unavailable. Check the internet connection and select Refresh to retry.");
                    if (manual && visible && !fullscreen) new android.app.AlertDialog.Builder(this).setTitle("Could not refresh suggestions")
                        .setMessage("Check the TV's internet connection, then select Refresh to retry.").setPositiveButton("OK", null).show();
                }
            }); }
        });
    }
    private String addresses() {
        try { for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) for (InetAddress ip : Collections.list(nic.getInetAddresses())) if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip.getHostAddress(); } catch (Exception ignored) {} return "No local network";
    }
    @Override public void confirmPair(String name, Runnable approve, Runnable deny) {
        if (pairingHelp != null) { pairingHelp.dismiss(); pairingHelp = null; }
        new android.app.AlertDialog.Builder(this).setTitle("Allow this phone?").setMessage(name + " wants to control LiTube playback on this TV.")
                .setPositiveButton("Allow", (d, w) -> approve.run()).setNegativeButton("Deny", (d, w) -> deny.run()).setOnCancelListener(d -> deny.run()).show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (video.hasFocus() && video.dispatchMediaKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}


