package com.hhst.youtubelite.cast;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import com.hhst.youtubelite.BuildConfig;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import dagger.hilt.android.AndroidEntryPoint;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;

/**
 * LiTube for Android TV, laid out like YouTube on TV: a navigation rail, rows of videos and a
 * full-screen player. The receiver service owns playback, so casting works across this screen's
 * lifecycle.
 */
@AndroidEntryPoint
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class TvActivity extends AppCompatActivity implements TvReceiverService.Screen, TvPlayerUi.Host, TvCards.Listener {
    private static final int SEARCH = 0, HOME = 1, ACCOUNT = 2, SETTINGS = 3;
    // Initialises the YouTube extractor before browsing.
    @Inject YoutubeExtractor extractor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService feed = Executors.newFixedThreadPool(3);
    private TvReceiverService service;
    private TvPlayerUi playerUi;
    private View browse, playerLayer, nav;
    private final View[] navItems = new View[4];
    private View[] pages;
    private VerticalGridView homeRows, searchResults;
    private TextView heroTitle, heroMeta, homeStatus, castChip, searchStatus;
    private EditText searchBox;
    private LinearLayout settingsList;
    private int page = HOME, searchColumns = 3;
    private boolean visible, resumed, playerOpen, navExpanded, homeFocused;
    private View lastBrowseFocus;
    private String pendingPlay, homeSeed;
    private long feedGeneration, searchGeneration;
    private final List<TvCards.Row> rows = new ArrayList<>();
    private final List<Integer> rowSlots = new ArrayList<>();
    private TvCards.RowAdapter rowAdapter;
    private AlertDialog pairDialog;
    private final Runnable attach = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (TvReceiverService.instance == null) { main.postDelayed(this, 300); return; }
            service = TvReceiverService.instance;
            playerUi.attach(service.player());
            service.attach(TvActivity.this);
            service.setOnScreen(resumed);
            if (pendingPlay != null) { String id = pendingPlay; pendingPlay = null; service.playLocal(id); openPlayer(); }
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true);
        setContentView(R.layout.activity_tv);
        browse = findViewById(R.id.tv_browse);
        playerLayer = findViewById(R.id.tv_player_layer);
        nav = findViewById(R.id.tv_nav);
        pages = new View[]{findViewById(R.id.tv_page_search), findViewById(R.id.tv_page_home), null, findViewById(R.id.tv_page_settings)};
        homeRows = findViewById(R.id.tv_home_rows);
        searchResults = findViewById(R.id.tv_search_results);
        heroTitle = findViewById(R.id.tv_hero_title);
        heroMeta = findViewById(R.id.tv_hero_meta);
        homeStatus = findViewById(R.id.tv_home_status);
        castChip = findViewById(R.id.tv_cast_chip);
        searchStatus = findViewById(R.id.tv_search_status);
        searchBox = findViewById(R.id.tv_search_box);
        settingsList = findViewById(R.id.tv_settings_list);
        playerUi = new TvPlayerUi(this, this);
        setupNav();
        TvCards.alignStart(homeRows);
        rowAdapter = new TvCards.RowAdapter(rows, this);
        homeRows.setAdapter(rowAdapter);
        float widthDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        searchColumns = Math.max(2, (int) ((widthDp - 104 - 48) / 244));
        searchResults.setNumColumns(searchColumns);
        searchResults.setVerticalSpacing(Math.round(28 * getResources().getDisplayMetrics().density));
        searchBox.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) { search(); return true; }
            return false;
        });
        playerLayer.setFocusable(true);
        showPage(HOME);
        // Without this the first focusable item, Search, would take focus and switch pages.
        navItems[HOME].requestFocus();
        loadHome(true);
        ContextCompat.startForegroundService(this, new Intent(this, TvReceiverService.class));
        if (saved != null && saved.getBoolean("player")) openPlayer();
    }

    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("player", playerOpen); super.onSaveInstanceState(out); }
    @Override protected void onStart() { super.onStart(); visible = true; main.post(attach); }
    @Override protected void onPause() {
        // The Input menu pauses LiTube about two seconds before the TV switches to HDMI; the
        // video decoder must be free by then (see TvReceiverService.setOnScreen).
        resumed = false;
        if (service != null) service.setOnScreen(false);
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (service != null) service.setOnScreen(true);
        // Recently watched videos change while the player is used.
        List<TvVideo> history = TvReceiverService.history(this);
        String seed = history.isEmpty() ? null : history.get(0).id();
        if (!playerOpen && !Objects.equals(seed, homeSeed)) loadHome(false);
    }
    @Override protected void onStop() {
        visible = false; main.removeCallbacks(attach);
        if (service != null) service.detach(this);
        playerUi.detach();
        super.onStop();
    }
    @Override protected void onDestroy() { feedGeneration++; searchGeneration++; feed.shutdownNow(); super.onDestroy(); }

    // ---- Navigation rail ----

    private void setupNav() {
        int[] ids = {R.id.nav_search, R.id.nav_home, R.id.nav_account, R.id.nav_settings};
        int[] icons = {R.drawable.ic_tv_search, R.drawable.ic_tv_home, R.drawable.ic_tv_account, R.drawable.ic_tv_settings};
        String[] labels = {"Search", "Home", "Account", "Settings"};
        for (int i = 0; i < ids.length; i++) {
            View item = findViewById(ids[i]);
            navItems[i] = item;
            ((ImageView) item.findViewById(R.id.nav_icon)).setImageResource(icons[i]);
            ((TextView) item.findViewById(R.id.nav_label)).setText(labels[i]);
            item.setContentDescription(labels[i]);
            int index = i;
            item.setOnFocusChangeListener((v, focused) -> {
                if (focused && index != ACCOUNT) showPage(index);
                main.post(this::updateNav);
            });
            item.setOnClickListener(v -> {
                if (index == ACCOUNT) startActivity(new Intent(this, TvAccountActivity.class));
                else focusPage();
            });
        }
    }
    /** The rail shows labels while it has focus, like YouTube on TV. */
    private void updateNav() {
        boolean expand = nav.hasFocus();
        if (expand == navExpanded) return;
        navExpanded = expand;
        for (View item : navItems) item.findViewById(R.id.nav_label).setVisibility(expand ? View.VISIBLE : View.GONE);
        nav.setBackgroundResource(expand ? R.drawable.tv_nav_expanded_bg : 0);
    }
    private void showPage(int index) {
        if (index == ACCOUNT) return;
        page = index;
        for (int i = 0; i < pages.length; i++) if (pages[i] != null) pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navItems.length; i++) navItems[i].setSelected(i == index);
        if (index == SETTINGS) renderSettings();
        // The search box spans the top of its page, where the badge would sit.
        castChip.setVisibility(index == SEARCH ? View.GONE : View.VISIBLE);
    }
    private void focusPage() {
        if (page == HOME && rows.isEmpty()) return;
        View target = page == SEARCH ? (searchResults.getAdapter() != null && searchResults.getAdapter().getItemCount() > 0 ? searchResults : searchBox)
                : page == HOME ? homeRows : settingsList.getChildCount() > 1 ? settingsList.getChildAt(1) : null;
        if (target != null) target.requestFocus();
    }
    private void focusNav() { navItems[page].requestFocus(); }

    // ---- Home ----

    private record Slot(String title, Loader loader) {}
    private interface Loader { List<TvVideo> load() throws Exception; }

    /** Builds the rows, each loaded independently and shown in a fixed order as it arrives. */
    private void loadHome(boolean first) {
        long request = ++feedGeneration;
        List<TvVideo> history = TvReceiverService.history(this);
        homeSeed = history.isEmpty() ? null : history.get(0).id();
        boolean hadFocus = homeRows.hasFocus();
        rows.clear(); rowSlots.clear(); rowAdapter.notifyDataSetChanged();
        homeStatus.setVisibility(View.VISIBLE); homeStatus.setText("Loading videos…");
        homeFocused = !first && !hadFocus;
        List<Slot> slots = new ArrayList<>();
        if (!history.isEmpty()) {
            List<TvVideo> recent = new ArrayList<>(history.subList(0, Math.min(history.size(), 20)));
            slots.add(new Slot("Continue watching", () -> recent));
            TvVideo last = history.get(0);
            slots.add(new Slot("Recommended for you", () -> TvSuggestions.related(last.id())));
        }
        slots.add(new Slot("Trending", TvSuggestions::trending));
        slots.add(new Slot("Music", () -> TvSuggestions.search("music")));
        if (history.size() > 1) { TvVideo earlier = history.get(1); slots.add(new Slot("Because you watched " + earlier.title(), () -> TvSuggestions.related(earlier.id()))); }
        slots.add(new Slot("Gaming", () -> TvSuggestions.search("gaming")));
        slots.add(new Slot("News", () -> TvSuggestions.search("news today")));
        slots.add(new Slot("Live", () -> TvSuggestions.search("live")));
        slots.add(new Slot("Sports", () -> TvSuggestions.search("sports highlights")));
        int[] pending = {slots.size()};
        for (int i = 0; i < slots.size(); i++) {
            int slot = i; Slot source = slots.get(i);
            feed.execute(() -> {
                List<TvVideo> items;
                try { items = source.loader().load(); } catch (Exception e) { items = List.of(); }
                List<TvVideo> loaded = items;
                main.post(() -> {
                    if (isDestroyed() || request != feedGeneration) return;
                    pending[0]--;
                    if (!loaded.isEmpty()) addRow(slot, new TvCards.Row(source.title(), loaded));
                    if (!rows.isEmpty()) homeStatus.setVisibility(View.GONE);
                    else if (pending[0] == 0) homeStatus.setText("Couldn't load videos. Check the TV's internet connection, then open Home again.");
                });
            });
        }
    }
    private void addRow(int slot, TvCards.Row row) {
        int position = 0;
        while (position < rowSlots.size() && rowSlots.get(position) < slot) position++;
        rowSlots.add(position, slot); rows.add(position, row);
        rowAdapter.notifyItemInserted(position);
        if (rows.size() == 1 && heroTitle.getText().length() == 0) focused(row.items().get(0));
        // Start in the content, as YouTube does, unless the viewer is already elsewhere.
        if (!homeFocused && !playerOpen && page == HOME) {
            View current = getCurrentFocus();
            if (current == null || current.getParent() == nav || nav.hasFocus()) {
                homeFocused = true;
                homeRows.post(() -> { if (!playerOpen && page == HOME) homeRows.requestFocus(); });
            }
        }
    }

    @Override public void open(TvVideo video) { play(video); }
    @Override public void focused(TvVideo video) {
        heroTitle.setText(video.title());
        heroMeta.setText(video.meta().isEmpty() ? video.channel() : video.channel() + "  •  " + video.meta());
    }

    // ---- Search ----

    private void search() {
        String term = searchBox.getText().toString().trim();
        if (term.isEmpty()) return;
        InputMethodManager input = getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        try { String id = CastProtocol.videoId(term); play(TvVideo.of(id, "", "", 0)); return; } catch (IllegalArgumentException ignored) { }
        long request = ++searchGeneration;
        searchStatus.setText("Searching for “" + term + "”…");
        searchResults.setAdapter(null);
        feed.execute(() -> {
            List<TvVideo> found; String failure = null;
            try { found = TvSuggestions.search(term); } catch (Exception e) { found = List.of(); failure = "Search failed. Check the TV's internet connection and try again."; }
            List<TvVideo> items = found; String error = failure;
            main.post(() -> {
                if (isDestroyed() || request != searchGeneration) return;
                if (error != null) { searchStatus.setText(error); return; }
                searchStatus.setText(items.isEmpty() ? "No results for “" + term + "”" : "Results for “" + term + "”");
                searchResults.setAdapter(new TvCards.CardAdapter(items, this));
                if (!items.isEmpty() && page == SEARCH && !playerOpen) searchResults.post(searchResults::requestFocus);
            });
        });
    }

    // ---- Settings ----

    private void renderSettings() {
        View focused = settingsList.getFocusedChild();
        int focusIndex = focused == null ? -1 : settingsList.indexOfChild(focused);
        while (settingsList.getChildCount() > 1) settingsList.removeViewAt(1);
        boolean on = service != null && TvReceiverService.instance != null;
        setting(R.drawable.ic_cast, "Link with phone", "On your phone, open LiTube, play a video and tap the Cast button", this::showLinkInfo);
        setting(R.drawable.ic_next, "Autoplay", TvReceiverService.autoplay(this) ? "On — the next suggested video plays automatically" : "Off", () -> {
            TvReceiverService.setAutoplay(this, !TvReceiverService.autoplay(this)); renderSettings();
        });
        setting(R.drawable.ic_cast_connected, "Casting", on ? "On — phones on this Wi-Fi can cast to this TV" : "Off — select to turn on", () -> {
            if (on) {
                playerUi.detach(); stopService(new Intent(this, TvReceiverService.class)); service = null; changed(); renderSettings();
            } else {
                ContextCompat.startForegroundService(this, new Intent(this, TvReceiverService.class)); main.post(attach); main.postDelayed(this::renderSettings, 800);
            }
        });
        setting(R.drawable.ic_close, "Remove linked devices", "Phones and computers will need approval again", () ->
                new AlertDialog.Builder(this).setTitle("Remove linked devices?").setMessage("Every phone and computer will need to be allowed on this TV again before it can cast.")
                        .setPositiveButton("Remove", (d, w) -> { if (service != null) service.forgetPhones(); }).setNegativeButton("Cancel", null).show());
        if (Build.VERSION.SDK_INT >= 29) setting(R.drawable.ic_tv, "Open when a phone casts", Settings.canDrawOverlays(this) ? "Allowed" : "Allow LiTube to open over other apps", () -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { info("Not available", "This TV doesn't offer that permission. Keep LiTube open to receive videos."); }
        });
        setting(R.drawable.ic_info, "Wake from standby", "Let a phone turn this TV on", () -> info("Wake from standby",
                "Keep casting turned on. On Sony TVs, turn on Remote Start in Settings > Network. A phone can then wake the TV when it casts, as long as the TV keeps its network on in standby. A TV that is unplugged can't be woken."));
        setting(R.drawable.ic_info, "About", "LiTube " + BuildConfig.VERSION_NAME, () -> info("LiTube", "Version " + BuildConfig.VERSION_NAME + "\nTV name: " + TvReceiverService.tvName(this)));
        if (focusIndex > 0 && focusIndex < settingsList.getChildCount()) settingsList.getChildAt(focusIndex).requestFocus();
    }
    private void setting(int icon, String title, String summary, Runnable action) {
        View item = LayoutInflater.from(this).inflate(R.layout.tv_setting_item, settingsList, false);
        ((ImageView) item.findViewById(R.id.tv_setting_icon)).setImageResource(icon);
        ((TextView) item.findViewById(R.id.tv_setting_title)).setText(title);
        ((TextView) item.findViewById(R.id.tv_setting_summary)).setText(summary);
        item.setOnClickListener(v -> action.run());
        settingsList.addView(item);
    }
    private void showLinkInfo() {
        String address = service == null ? "Casting is off" : addresses() + ":" + service.port();
        String code = service == null ? "—" : service.pairingCode();
        info("Link with phone", "1. Connect your phone to the same Wi-Fi as this TV.\n2. In LiTube on your phone, play a video and tap the Cast button.\n3. Pick “" + TvReceiverService.tvName(this) + "”, then select Allow here.\n\n"
                + "If your TV isn't listed, choose Connect with TV code on the phone and enter:\n\nTV address: " + address + "\nTV code: " + code);
    }
    private void info(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }

    // ---- Player ----

    @Override public TvReceiverService service() { return service; }
    @Override public void play(TvVideo video) {
        if (service == null || TvReceiverService.instance == null) {
            pendingPlay = video.id();
            ContextCompat.startForegroundService(this, new Intent(this, TvReceiverService.class));
            main.post(attach);
            return;
        }
        service.playLocal(video.id());
        openPlayer();
    }
    private void openPlayer() {
        if (!playerOpen) lastBrowseFocus = getCurrentFocus();
        playerOpen = true;
        playerLayer.setVisibility(View.VISIBLE);
        browse.setVisibility(View.GONE);
        playerUi.hideOverlay();
        playerLayer.requestFocus();
        playerUi.update();
    }
    private void closePlayer() {
        playerOpen = false;
        if (service != null) service.player().pause();
        playerUi.hideOverlay();
        playerLayer.setVisibility(View.GONE);
        browse.setVisibility(View.VISIBLE);
        if (lastBrowseFocus != null && lastBrowseFocus.isAttachedToWindow()) lastBrowseFocus.requestFocus(); else focusPage();
        List<TvVideo> history = TvReceiverService.history(this);
        String seed = history.isEmpty() ? null : history.get(0).id();
        if (!Objects.equals(seed, homeSeed) && page == HOME) loadHome(false);
    }

    // ---- Receiver callbacks ----

    @Override public void showCastFullscreen() {
        if (pairDialog != null) { pairDialog.dismiss(); pairDialog = null; }
        openPlayer();
        playerUi.showOverlay();
    }
    @Override public void changed() {
        if (!visible) return;
        boolean on = service != null && TvReceiverService.instance != null;
        castChip.setText(!on ? "Casting is off" : service.ready() ? "Ready to cast  •  " + service.tvName() : "Starting…");
        // The phone stopped casting: leave the empty player, as YouTube does.
        if (playerOpen && on && service.currentVideoId() == null) closePlayer();
        if (playerOpen) playerUi.update();
        boolean playing = on && service.player().isPlaying();
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void confirmPair(String name, String code, Runnable approve, Runnable deny) {
        if (pairDialog != null) pairDialog.dismiss();
        boolean[] answered = {false};
        pairDialog = new AlertDialog.Builder(this).setTitle("Allow " + name + " to cast?")
                .setMessage(name + " wants to play videos on this TV.\n\nCode: " + code + "\n\nCheck that your device shows the same code.")
                .setPositiveButton("Allow", (d, w) -> { answered[0] = true; approve.run(); })
                .setNegativeButton("Don't allow", (d, w) -> { answered[0] = true; deny.run(); })
                .setOnDismissListener(d -> { if (!answered[0]) deny.run(); pairDialog = null; })
                .show();
    }

    // ---- Remote keys ----

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (playerOpen) {
            if (playerUi.dispatch(event)) return true;
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (event.getAction() == KeyEvent.ACTION_UP) closePlayer(); return true; }
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View current = getCurrentFocus();
            boolean inNav = nav.hasFocus();
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT -> { if (!inNav && current != null && atLeftEdge(current)) { focusNav(); return true; } }
                case KeyEvent.KEYCODE_DPAD_RIGHT -> { if (inNav) { focusPage(); return true; } }
                case KeyEvent.KEYCODE_SEARCH -> { showPage(SEARCH); searchBox.requestFocus(); return true; }
                default -> { }
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !nav.hasFocus()) {
            if (event.getAction() == KeyEvent.ACTION_UP) focusNav();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    /** True when moving left would leave the page content for the navigation rail. */
    private boolean atLeftEdge(View focused) {
        if (focused == searchBox) return searchBox.getSelectionStart() == 0;
        View child = focused; ViewParent parent = focused.getParent();
        while (parent instanceof View && !(parent instanceof RecyclerView)) { child = (View) parent; parent = parent.getParent(); }
        if (!(parent instanceof RecyclerView grid)) return true;
        int position = grid.getChildAdapterPosition(child);
        if (grid == searchResults) return position % searchColumns == 0;
        return position <= 0;
    }

    private static String addresses() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress ip : Collections.list(nic.getInetAddresses()))
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip.getHostAddress();
        } catch (Exception ignored) { }
        return "No local network";
    }
}
