package com.hhst.youtubelite.player;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import com.hhst.youtubelite.extractor.*;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.controller.Controller;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.tencent.mmkv.MMKV;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LitePlayerRecoveryTest {
    private final Activity activity = mock(Activity.class);
    private final Engine engine = mock(Engine.class);
    private final YoutubeExtractor extractor = mock(YoutubeExtractor.class);
    private final MMKV kv = mock(MMKV.class);
    private final CompletableFuture<PlaybackDetails> refreshed = new CompletableFuture<>();
    private LitePlayer player;
    private Player.Listener listener;

    @Before
    public void setUp() throws Exception {
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(activity).runOnUiThread(any());
        when(engine.position()).thenReturn(60_000L);
        when(engine.getPlaybackRate()).thenReturn(1.5f);
        when(engine.getPlayWhenReady()).thenReturn(true);
        when(engine.recoverFromPlaybackError(any())).thenReturn(true);
        when(extractor.refreshInfo(anyString(), any())).thenReturn(refreshed);
        when(kv.decodeString("last_audio_lang", "und")).thenReturn("en");
        try (MockedStatic<MMKV> mmkv = mockStatic(MMKV.class)) {
            mmkv.when(MMKV::defaultMMKV).thenReturn(kv);
            player = new LitePlayer(activity, extractor, mock(LitePlayerView.class),
                    mock(Controller.class), engine, mock(SponsorBlockManager.class),
                    mock(QueueRepository.class), mock(PlayerPreferences.class),
                    mock(PlayerStateStore.class), Runnable::run);
        }
        setField("activeId", "abcdefghijk");
        setField("queuedId", "abcdefghijk");
        ArgumentCaptor<Player.Listener> capture = ArgumentCaptor.forClass(Player.Listener.class);
        verify(engine).addListener(capture.capture());
        listener = capture.getValue();
    }

    @Test
    public void forbiddenRefreshesAndResumesAtFailurePositionWithSpeedAndPlayIntent() {
        listener.onPlayerError(error(403));
        verify(extractor).refreshInfo(eq("https://www.youtube.com/watch?v=abcdefghijk"), any());
        PlaybackDetails details = details();
        try (MockedStatic<PlaybackPlanner> planner = mockStatic(PlaybackPlanner.class)) {
            planner.when(() -> PlaybackPlanner.plan(details.deliveries(), null, "en")).thenReturn(details.plan());
            refreshed.complete(details);
        }
        verify(engine).resumeWithFreshDetails(any(), eq(60_000L), eq(1.5f), eq(true));
        verify(engine, never()).recoverFromPlaybackError(any());
    }

    @Test
    public void duplicateErrorsDuringRefreshDoNotStartAnotherRecovery() {
        listener.onPlayerError(error(403));
        listener.onPlayerError(error(403));
        verify(extractor, times(1)).refreshInfo(anyString(), any());
        verify(engine, never()).recoverFromPlaybackError(any());
    }

    @Test
    public void unrelatedHttpErrorsDoNotRefresh() {
        listener.onPlayerError(error(404));
        verify(extractor, never()).refreshInfo(anyString(), any());
    }

    @Test
    public void secondForbiddenAfterRefreshUsesFallbackInsteadOfRefreshingForever() {
        listener.onPlayerError(error(403));
        completeRefresh();
        PlaybackException second = error(403);
        listener.onPlayerError(second);
        verify(extractor, times(1)).refreshInfo(anyString(), any());
        verify(engine).recoverFromPlaybackError(second);
    }

    @Test
    public void pauseDuringRefreshIsRespected() {
        listener.onPlayerError(error(403));
        when(engine.getPlayWhenReady()).thenReturn(false);
        completeRefresh();
        verify(engine).resumeWithFreshDetails(any(), eq(60_000L), eq(1.5f), eq(false));
    }

    private void completeRefresh() {
        PlaybackDetails details = details();
        try (MockedStatic<PlaybackPlanner> planner = mockStatic(PlaybackPlanner.class)) {
            planner.when(() -> PlaybackPlanner.plan(details.deliveries(), null, "en")).thenReturn(details.plan());
            refreshed.complete(details);
        }
    }

    @Test
    public void switchingVideosIgnoresOldRefresh() throws Exception {
        listener.onPlayerError(error(403));
        setField("activeId", "lmnopqrstuv");
        setField("queuedId", "lmnopqrstuv");
        refreshed.complete(details());
        verify(engine, never()).resumeWithFreshDetails(any(), anyLong(), anyFloat(), anyBoolean());
    }

    @Test
    public void cancelledRefreshDoesNotRestartPlayback() {
        listener.onPlayerError(error(403));
        ArgumentCaptor<ExtractionSession> capture = ArgumentCaptor.forClass(ExtractionSession.class);
        verify(extractor).refreshInfo(anyString(), capture.capture());
        capture.getValue().cancel();
        refreshed.complete(details());
        verify(engine, never()).resumeWithFreshDetails(any(), anyLong(), anyFloat(), anyBoolean());
    }

    @Test
    public void refreshFailureUsesExistingFallback() {
        PlaybackException error = error(403);
        listener.onPlayerError(error);
        refreshed.completeExceptionally(new IllegalStateException("Extraction failed"));
        verify(engine).recoverFromPlaybackError(error);
    }

    private PlaybackException error(int status) {
        HttpDataSource.InvalidResponseCodeException http = new HttpDataSource.InvalidResponseCodeException(
                status, "Rejected", null, Map.of(), mock(DataSpec.class), new byte[0]);
        PlaybackException error = mock(PlaybackException.class);
        when(error.getCause()).thenReturn(http);
        when(error.getSuppressed()).thenReturn(new Throwable[0]);
        return error;
    }

    private PlaybackDetails details() {
        return new PlaybackDetails(mock(VideoDetails.class), mock(StreamCatalog.class),
                mock(DeliveryCatalog.class), mock(PlaybackPlan.class), List.of(), List.of());
    }

    private void setField(String name, Object value) throws Exception {
        Field field = LitePlayer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }
}
