package com.hhst.youtubelite.player.engine;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.hhst.youtubelite.extractor.Delivery;
import com.hhst.youtubelite.extractor.PlaybackMode;
import com.hhst.youtubelite.extractor.PlaybackPlan;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Before;
import org.junit.Test;

public class HlsQualitySelectionTest {
    private Engine engine;
    private PlayerPreferences prefs;
    private PlaybackPlan plan;

    @Before public void setup() throws Exception {
        engine = mock(Engine.class, CALLS_REAL_METHODS);
        prefs = mock(PlayerPreferences.class);
        plan = new PlaybackPlan();
        plan.setMode(PlaybackMode.VOD_HLS);
        Delivery delivery = new Delivery();
        delivery.setMode(PlaybackMode.VOD_HLS);
        delivery.setTrackLock(false);
        plan.setDelivery(delivery);
        field("prefs").set(engine, prefs);
        field("playbackPlan").set(engine, plan);
        doNothing().when(engine).setVideoQuality(anyInt());
    }

    @Test public void selecting720pLocksTrackWithoutReplacingActiveDelivery() throws Exception {
        when(prefs.getPreferredQuality()).thenReturn("720p");
        engine.onQualitySelected("720p");
        verify(prefs).setPreferredQuality("720p");
        verify(engine).setVideoQuality(720);
        assertSame(plan, field("playbackPlan").get(engine));
        verify(engine, never()).play(any());
    }

    @Test public void selecting1080pDoesNotRemainAnAdaptiveCeiling() {
        when(prefs.getPreferredQuality()).thenReturn("1080p");
        engine.onQualitySelected("1080p");
        verify(engine).setVideoQuality(1080);
    }

    @Test public void arrivingTracksReapplySavedHlsSelection() throws Exception {
        when(prefs.getPreferredQuality()).thenReturn("720p");
        Method method = Engine.class.getDeclaredMethod("applyPreferredVideoTrack");
        method.setAccessible(true);
        method.invoke(engine);
        verify(engine).setVideoQuality(720);
    }

    private static Field field(String name) throws Exception {
        Field field = Engine.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
