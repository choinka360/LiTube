package com.hhst.youtubelite.extractor;

import static org.junit.Assert.*;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.hls.HlsDataSourceFactory;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.schabi.newpipe.extractor.NewPipe;
import okhttp3.OkHttpClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

public class VisionOsHlsDeviceTest {
    @Test public void suppliedClipPlaysBeyondThreeMinutes() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var arguments = InstrumentationRegistry.getArguments();
        String videoId = arguments.getString("videoId", "z0Eps7x1Tso");
        int height = Integer.parseInt(arguments.getString("height", "720"));
        long targetMs = Long.parseLong(arguments.getString("durationSeconds", "180")) * 1000;
        boolean injectFault = Boolean.parseBoolean(arguments.getString("injectFault", "false"));
        DownloaderImpl downloader = new DownloaderImpl(new OkHttpClient(), new ExtractionSessionScope());
        NewPipe.init(downloader);
        String manifest = new VisionOsHls(downloader).fetch(videoId, new ExtractionSession());
        assertNotNull("VisionOS did not return an HLS manifest", manifest);
        var create = Class.forName("com.hhst.youtubelite.player.engine.PlayerLoadControl").getDeclaredMethod("create");
        create.setAccessible(true);
        LoadControl loadControl = (LoadControl) create.invoke(null);
        AtomicReference<ExoPlayer> player = new AtomicReference<>();
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        AtomicLong position = new AtomicLong();
        AtomicLong selectedHeight = new AtomicLong();
        AtomicLong maxBufferMs = new AtomicLong();
        AtomicLong rebuffers = new AtomicLong();
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean qualityLocked = new AtomicBoolean();
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean delayed = new AtomicBoolean();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(VisionOsHls.USER_AGENT).setConnectTimeoutMs(30_000).setReadTimeoutMs(45_000);
        HlsDataSourceFactory sources = type -> new DataSource() {
            final DataSource upstream = http.createDataSource();
            boolean failedThisSource;
            boolean delayedThisSource;
            @Override public long open(DataSpec spec) throws IOException {
                if (type == C.DATA_TYPE_MEDIA && armed.get()) {
                    if (!failedThisSource) {
                        failedThisSource = true;
                        failed.set(true);
                        Log.i("YTLPlayback", "TEST injecting one HLS segment timeout");
                        throw new SocketTimeoutException("Injected HLS regression-test timeout");
                    }
                    if (!delayedThisSource) {
                        delayedThisSource = true;
                        delayed.set(true);
                        Log.i("YTLPlayback", "TEST delaying the retry by 15 seconds");
                        try { Thread.sleep(15_000); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    }
                }
                return upstream.open(spec);
            }
            @Override public int read(byte[] b, int off, int len) throws IOException { return upstream.read(b, off, len); }
            @Override public Uri getUri() { return upstream.getUri(); }
            @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
            @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
            @Override public void close() throws IOException { upstream.close(); }
        };
        try {
            instrumentation.runOnMainSync(() -> {
                DefaultTrackSelector selector = new DefaultTrackSelector(instrumentation.getTargetContext());
                selector.setParameters(selector.buildUponParameters().setMinVideoSize(0, height)
                        .setMaxVideoSize(Integer.MAX_VALUE, height));
                ExoPlayer engine = new ExoPlayer.Builder(instrumentation.getTargetContext())
                        .setTrackSelector(selector).setLoadControl(loadControl).build();
                player.set(engine);
                engine.setVolume(0f);
                engine.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException failure) { error.set(failure); }
                    @Override public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) ready.set(true);
                        if (state == Player.STATE_BUFFERING && ready.get()) rebuffers.incrementAndGet();
                    }
                    @Override public void onTracksChanged(Tracks tracks) {
                        for (Tracks.Group group : tracks.getGroups()) {
                            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                            for (int i = 0; i < group.length; i++) {
                                if (group.getTrackFormat(i).height == height && group.isTrackSupported(i)
                                        && qualityLocked.compareAndSet(false, true)) {
                                    selector.setParameters(selector.buildUponParameters().setOverrideForType(
                                            new TrackSelectionOverride(group.getMediaTrackGroup(), i)));
                                }
                            }
                        }
                    }
                });
                engine.setMediaSource(new HlsMediaSource.Factory(sources).createMediaSource(MediaItem.fromUri(manifest)));
                engine.prepare();
                engine.play();
            });
            long deadline = SystemClock.elapsedRealtime() + targetMs + 120_000;
            long nextLog = 0;
            while (SystemClock.elapsedRealtime() < deadline && error.get() == null) {
                instrumentation.runOnMainSync(() -> {
                    position.set(player.get().getCurrentPosition());
                    maxBufferMs.accumulateAndGet(player.get().getTotalBufferedDuration(), Math::max);
                    if (player.get().getVideoFormat() != null) selectedHeight.set(player.get().getVideoFormat().height);
                });
                if (injectFault && position.get() >= 60_000) armed.set(true);
                if (position.get() >= nextLog) {
                    Log.i("YTLPlayback", "HLS test positionMs=" + position.get() + " height=" + selectedHeight.get()
                            + " maxBufferMs=" + maxBufferMs.get() + " rebuffers=" + rebuffers.get());
                    nextLog += 30_000;
                }
                if (position.get() >= targetMs) break;
                Thread.sleep(1000);
            }
            assertNull("Playback failed: " + (error.get() == null ? "" : error.get().getErrorCodeName()), error.get());
            assertTrue("Playback stalled at " + position.get() + "ms", position.get() >= targetMs);
            assertEquals("Requested resolution was not selected", height, selectedHeight.get());
            assertTrue("Manual track override was not exercised", qualityLocked.get());
            if (injectFault) {
                assertTrue("Segment error not exercised", failed.get());
                assertTrue("Network delay not exercised", delayed.get());
                assertTrue("Buffer never exceeded the old 60-second maximum", maxBufferMs.get() > 65_000);
                assertEquals("Playback stopped to rebuffer", 0, rebuffers.get());
            }
        } finally {
            instrumentation.runOnMainSync(() -> { if (player.get() != null) player.get().release(); });
        }
    }
}
