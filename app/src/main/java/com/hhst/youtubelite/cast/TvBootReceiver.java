package com.hhst.youtubelite.cast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

/** Starts the casting receiver when a TV boots, so a phone can cast without anyone opening LiTube. */
public class TvBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // Phones start the receiver only when needed; a boot-time media service would be refused there.
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return;
        ContextCompat.startForegroundService(context, new Intent(context, TvReceiverService.class));
    }
}
