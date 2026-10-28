package com.hhst.youtubelite.cast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Buttons of the phone's "Casting to TV" notification. */
public class CastActionReceiver extends BroadcastReceiver {
    static final String PLAY = "com.hhst.youtubelite.cast.PLAY", PAUSE = "com.hhst.youtubelite.cast.PAUSE", STOP = "com.hhst.youtubelite.cast.STOP";
    @Override public void onReceive(Context context, Intent intent) {
        CastSession session = CastSession.get(context);
        String action = intent.getAction();
        if (STOP.equals(action)) session.stopCasting();
        else if (PLAY.equals(action) || PAUSE.equals(action)) session.setPlaying(session.videoId(), PLAY.equals(action));
    }
}
