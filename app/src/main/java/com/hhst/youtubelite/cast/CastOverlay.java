package com.hhst.youtubelite.cast;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.ui.PlayerView;
import com.hhst.youtubelite.R;

/** "Playing on Living Room TV" shown over the phone's video while casting, as in YouTube. */
public final class CastOverlay {
    private final LinearLayout view;
    private final TextView label;
    private final CastSession cast;
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    public CastOverlay(PlayerView playerView, CastSession cast) {
        this.cast = cast;
        Context context = playerView.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(0xF2000000);
        // The player's centre play/pause button sits here; the label goes just below it.
        View gap = new View(context);
        view.addView(gap, new LinearLayout.LayoutParams(1, (int) (40 * density)));
        label = new TextView(context); label.setTextColor(Color.WHITE); label.setTextSize(15); label.setGravity(Gravity.CENTER);
        label.setPadding((int) (24 * density), (int) (10 * density), (int) (24 * density), 0);
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_cast_connected, 0, 0, 0);
        label.setCompoundDrawablePadding((int) (10 * density));
        label.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        view.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay != null) overlay.addView(view, params); else playerView.addView(view, 1, params);
        update();
    }
    public void update() {
        boolean show = cast.connected();
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        String state = cast.loading() ? "Loading on " : "Playing on ";
        // One line: a second one would push the label up into the player's play/pause button.
        String via = cast.remote() ? " · via internet" : "";
        label.setText((cast.videoId().isEmpty() ? "Connected to " + cast.deviceName() : state + cast.deviceName()) + via);
    }
}
