package com.hhst.youtubelite.cast;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.hhst.youtubelite.R;

/** YouTube-on-TV palette for screens built in code: dark surfaces, white focus. */
final class TvTheme {
    static final int TEXT = Color.rgb(241, 241, 241);
    static final int MUTED = Color.rgb(170, 170, 170);
    static final int ACCENT = Color.rgb(255, 0, 51);
    static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    static Drawable background() { return new ColorDrawable(0xff0f0f0f); }
    static void text(TextView view, boolean secondary) {
        view.setTextColor(secondary ? MUTED : TEXT);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }
    static void button(Button button) {
        button.setAllCaps(false); button.setTextSize(18);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(ContextCompat.getColorStateList(button.getContext(), R.color.tv_on_focus_text));
        button.setStateListAnimator(null);
        button.setBackgroundResource(R.drawable.tv_setting_bg);
        button.setBackgroundTintList(null);
        button.setPadding(dp(button, 24), dp(button, 8), dp(button, 24), dp(button, 8));
        button.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.02f : 1f).scaleY(focused ? 1.02f : 1f).setDuration(140).start());
    }
}
