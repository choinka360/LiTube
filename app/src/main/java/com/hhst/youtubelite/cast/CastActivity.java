package com.hhst.youtubelite.cast;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/** "Cast with LiTube" share target: sends a shared YouTube link to the TV. */
public class CastActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String url = getIntent().getStringExtra("url");
        if (url == null) url = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        String id;
        try { id = CastProtocol.videoId(url == null ? "" : extractLink(url)); }
        catch (IllegalArgumentException e) { Toast.makeText(this, "Share a YouTube video link to cast it", Toast.LENGTH_LONG).show(); finish(); return; }
        long position = Math.max(0, getIntent().getLongExtra("positionMs", 0));
        CastSession session = CastSession.get(this);
        if (session.connected()) {
            session.cast(id, position);
            Toast.makeText(this, "Playing on " + session.deviceName(), Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        if (saved == null) CastPicker.show(this, id, position, this::finish);
    }
    /** Shared text often wraps the link in a sentence. */
    private static String extractLink(String text) {
        for (String part : text.split("\\s+")) if (part.startsWith("http://") || part.startsWith("https://")) return part;
        return text.trim();
    }
}
