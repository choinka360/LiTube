package com.hhst.youtubelite.player;

import org.junit.Test;
import static org.junit.Assert.*;
import com.hhst.youtubelite.extractor.exception.VideoUnavailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;

public class PremiereAvailabilityTest {
    @Test public void suppressedPremiereStatusIsShownWithoutAnApplicationFailure() {
        ParsingException failure = new ParsingException("Could not get visitorData");
        failure.addSuppressed(new ContentNotAvailableException("Got error LIVE_STREAM_OFFLINE: \"Premieres in 19 minutes\""));
        var result = LitePlayer.classifyExtractionException(failure);
        assertTrue(result instanceof VideoUnavailableException);
        assertEquals("Premieres in 19 minutes", result.getMessage());
    }
}
