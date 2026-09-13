package com.hhst.youtubelite.cast;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Shared TV palette and remote-focus depth; fullscreen video stays undecorated. */
final class TvTheme {
    static final int TEXT = Color.rgb(237, 245, 255);
    static final int MUTED = Color.rgb(157, 178, 200);
    static final int ACCENT = Color.rgb(64, 224, 208);
    static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    static GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff142e40, 0xff101a30, 0xff211b38});
    }
    static GradientDrawable card(View view, boolean focused) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            focused ? new int[]{0xff176b7b, 0xff424a8c} : new int[]{0xff25384e, 0xff192638});
        drawable.setCornerRadius(dp(view, 12));
        drawable.setStroke(dp(view, focused ? 2 : 1), focused ? ACCENT : 0xff40536c);
        return drawable;
    }
    static void text(TextView view, boolean secondary) {
        view.setTextColor(secondary ? MUTED : TEXT);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
    }
    static void button(Button button) {
        text(button, false); button.setAllCaps(false); button.setTextSize(16);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setStateListAnimator(null);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, card(button, true));
        states.addState(new int[]{android.R.attr.state_pressed}, card(button, true));
        states.addState(new int[]{}, card(button, false));
        button.setBackground(states); button.setElevation(dp(button, 3));
        button.setPadding(dp(button, 18), dp(button, 8), dp(button, 18), dp(button, 8));
        button.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f)
                .translationZ(dp(v, focused ? 9 : 0)).setDuration(140).start();
        });
    }
}
