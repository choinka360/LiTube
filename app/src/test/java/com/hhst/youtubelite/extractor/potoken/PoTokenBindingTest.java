package com.hhst.youtubelite.extractor.potoken;

import static org.junit.Assert.*;
import com.hhst.youtubelite.extractor.AuthContext;
import org.junit.Test;

public class PoTokenBindingTest {
    @Test public void anonymousPlaybackUsesVisitorData() {
        assertEquals("visitor", PoTokenCoordinator.streamingIdentifier(auth(false, "account"), "visitor"));
    }
    @Test public void signedInPlaybackUsesAccountSession() {
        assertEquals("account||user", PoTokenCoordinator.streamingIdentifier(auth(true, "account||user"), "visitor"));
    }
    @Test public void missingAccountSessionDoesNotMintWrongToken() {
        assertNull(PoTokenCoordinator.streamingIdentifier(auth(true, null), "visitor"));
        assertNull(PoTokenCoordinator.streamingIdentifier(auth(true, " "), "visitor"));
    }
    @Test public void noAuthSnapshotUsesVisitorData() {
        assertEquals("visitor", PoTokenCoordinator.streamingIdentifier(null, "visitor"));
    }
    private AuthContext auth(boolean loggedIn, String account) {
        return new AuthContext("test", null, "visitor", account, null, null, loggedIn, false, 0L);
    }
}