package com.hhst.youtubelite.cast;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.*;

public class TvAccountActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TvGoogleAccount account;
    private TextView status;
    private Button signIn, signOut;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); account = new TvGoogleAccount(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(64, 40, 64, 40); root.setBackground(TvTheme.background()); root.setClipChildren(false);
        TextView brand = new TextView(this); brand.setText("LiTube  /  Your account"); brand.setTextSize(28); brand.setTextColor(TvTheme.ACCENT); root.addView(brand);
        status = new TextView(this); TvTheme.text(status, false); status.setPadding(0, 24, 0, 24); status.setTextSize(24); status.setText("YouTube account"); root.addView(status);
        TextView help = new TextView(this); TvTheme.text(help, true); help.setPadding(0, 0, 0, 28); help.setTextSize(18); help.setText("Sign in using your phone. LiTube never asks for your Google password.\nAccount connection allows read-only YouTube account access. Google's public API does not provide your personalized Home feed."); root.addView(help);
        signIn = new Button(this); signIn.setText("Sign in with Google"); root.addView(signIn);
        signOut = new Button(this); signOut.setText("Sign out"); root.addView(signOut);
        Button back = new Button(this); back.setText("Back to LiTube TV"); root.addView(back); back.setOnClickListener(v -> finish());
        for (Button action : new Button[]{signIn, signOut, back}) { TvTheme.button(action); android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(-1, TvTheme.dp(action, 54)); params.setMargins(8, 10, 8, 10); action.setLayoutParams(params); }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        signIn.setOnClickListener(v -> run(() -> account.signIn((code, url) -> runOnUiThread(() -> {
            if (!isDestroyed()) status.setText("On your phone open:\n" + url + "\n\nEnter code: " + code + "\n\nWaiting for your approval…");
        }))));
        signOut.setOnClickListener(v -> run(account::signOut));
        run(() -> account.signedIn() ? account.channelName() : TvGoogleAccount.configured() ? "Ready to sign in" : "Google sign-in needs LiTube's registered TV client. Install the configured build to enable sign-in.");
    }
    private interface Work { String run() throws Exception; }
    private void run(Work action) {
        signIn.setEnabled(false); signOut.setEnabled(false);
        worker.execute(() -> {
            String result;
            try { result = action.run(); } catch (Exception e) { result = e instanceof InterruptedException ? "Sign-in cancelled" : e.getMessage(); }
            String message = result;
            runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) { status.setText(message); signIn.setEnabled(TvGoogleAccount.configured()); signOut.setEnabled(true); } });
        });
    }
    @Override protected void onDestroy() { worker.shutdownNow(); account.close(); super.onDestroy(); }
}
