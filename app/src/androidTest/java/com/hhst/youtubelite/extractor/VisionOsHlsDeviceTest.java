package com.hhst.youtubelite.extractor;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.schabi.newpipe.extractor.NewPipe;
import okhttp3.OkHttpClient;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

public class VisionOsHlsDeviceTest {
    @Test public void suppliedClipPlaysBeyondThreeMinutes() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        DownloaderImpl downloader = new DownloaderImpl(new OkHttpClient(), new ExtractionSessionScope());
        NewPipe.init(downloader);
        String videoId = InstrumentationRegistry.getArguments().getString("videoId", "z0Eps7x1Tso");
        int height = Integer.parseInt(InstrumentationRegistry.getArguments().getString("height", "720"));
        String manifest = new VisionOsHls(downloader).fetch(videoId, new ExtractionSession());
        assertNotNull("VisionOS did not return an HLS manifest", manifest);
        AtomicReference<ExoPlayer> player = new AtomicReference<>();
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        AtomicLong position = new AtomicLong();
        AtomicLong selectedHeight = new AtomicLong();
        try {
            instrumentation.runOnMainSync(() -> {
                DefaultTrackSelector selector = new DefaultTrackSelector(instrumentation.getTargetContext());
                selector.setParameters(selector.buildUponParameters().setMinVideoSize(0, height)
                        .setMaxVideoSize(Integer.MAX_VALUE, height));
                ExoPlayer engine = new ExoPlayer.Builder(instrumentation.getTargetContext()).setTrackSelector(selector).build();
                player.set(engine);
                engine.setVolume(0f);
                engine.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException failure) { error.set(failure); }
                });
                engine.setMediaSource(new HlsMediaSource.Factory(new DefaultHttpDataSource.Factory()
                        .setUserAgent(VisionOsHls.USER_AGENT)).createMediaSource(MediaItem.fromUri(manifest)));
                engine.prepare();
                engine.play();
            });
            long deadline = SystemClock.elapsedRealtime() + 240_000;
            long nextLog = 0;
            while (SystemClock.elapsedRealtime() < deadline && error.get() == null) {
                instrumentation.runOnMainSync(() -> {
                    position.set(player.get().getCurrentPosition());
                    if (player.get().getVideoFormat() != null) selectedHeight.set(player.get().getVideoFormat().height);
                });
                if (position.get() >= nextLog) {
                    Log.i("YTLPlayback", "HLS device test positionMs=" + position.get() + " height=" + selectedHeight.get());
                    nextLog += 30_000;
                }
                if (position.get() >= 180_000) break;
                Thread.sleep(1000);
            }
            assertNull("Playback failed: " + (error.get() == null ? "" : error.get().getErrorCodeName()), error.get());
            assertEquals("Requested resolution was not selected", height, selectedHeight.get());
            assertTrue("Playback stalled at " + position.get() + "ms", position.get() >= 180_000);
        } finally {
            instrumentation.runOnMainSync(() -> { if (player.get() != null) player.get().release(); });
        }
    }
}