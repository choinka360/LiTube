package com.hhst.youtubelite.cast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Calendar;
import java.util.List;

public class RemoteCommandTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test public void signedCommandRoundTrips() {
        String packet = RemoteCommand.sign(KEY, "LTPLAY", 1793165818090L, "WqbreABNlX4", "70000");
        RemoteCommand.Parsed parsed = RemoteCommand.verify(KEY, packet);
        assertNotNull(parsed);
        assertEquals("LTPLAY", parsed.name());
        assertEquals(1793165818090L, parsed.timestamp());
        assertEquals(List.of("WqbreABNlX4", "70000"), parsed.args());
        assertEquals(List.of(), RemoteCommand.verify(KEY, RemoteCommand.sign(KEY, "LTSTOP", 5)).args());
        assertEquals(List.of("-1"), RemoteCommand.verify(KEY, RemoteCommand.sign(KEY, "LTVOL", 5, "-1")).args());
    }

    @Test public void rejectsTamperingWrongKeysAndOversizedPackets() {
        String packet = RemoteCommand.sign(KEY, "LTPLAY", 100, "WqbreABNlX4", "0");
        assertNull(RemoteCommand.verify(KEY, packet.replace("WqbreABNlX4", "AAAAAAAAAAA")));
        assertNull(RemoteCommand.verify(KEY, packet.replace(":100:", ":101:")));
        assertNull(RemoteCommand.verify("fedcba9876543210fedcba9876543210", packet));
        assertNull(RemoteCommand.verify("", packet));
        assertNull(RemoteCommand.verify(KEY, "x".repeat(RemoteCommand.MAX_PACKET + 1)));
        assertNull(RemoteCommand.verify(KEY, "LTPLAY:100:a:b:c:" + "0".repeat(64)));
        assertNull(RemoteCommand.verify(KEY, "LTPLAY:100:../etc:" + "0".repeat(64)));
    }

    /** Same regex and signed text as the RemoteWakeUp helper (AgentService.handle). */
    @Test public void wakeCommandMatchesTheTvHelper() {
        long ts = 1793165818090L;
        String packet = RemoteCommand.sign(KEY, "TVWAKE", ts);
        assertTrue(packet.matches("^(TVWAKE|TVSLEEP|STATUS):(\\d{1,15}):([0-9a-f]{64})$"));
        assertEquals("TVWAKE:" + ts + ":" + RemoteCommand.hmac(KEY, "TVWAKE:" + ts), packet);
    }

    @Test public void freshnessIsCheckedOnlyOnceTheClockIsSet() {
        Calendar booted = Calendar.getInstance(); booted.set(2023, Calendar.JANUARY, 1);
        long now = System.currentTimeMillis();
        assertTrue("TV clock still in 2023 after boot", RemoteCommand.fresh(now, booted.getTimeInMillis()));
        assertTrue(RemoteCommand.fresh(now - 60_000, now));
        assertFalse(RemoteCommand.fresh(now - 10 * 60_000, now));
        assertFalse(RemoteCommand.fresh(now + 10 * 60_000, now));
    }

    @Test public void repeatsGetTheCachedReplyAndOlderCommandsAreIgnored() {
        RemoteCommand.Guard guard = new RemoteCommand.Guard(100);
        assertEquals(RemoteCommand.Guard.Verdict.IGNORE, guard.check(100));
        assertEquals(RemoteCommand.Guard.Verdict.IGNORE, guard.check(99));
        assertEquals(RemoteCommand.Guard.Verdict.RUN, guard.check(101));
        guard.accepted(101);
        assertEquals("A copy arriving before the reply is ready is dropped", RemoteCommand.Guard.Verdict.IGNORE, guard.check(101));
        guard.replied(101, "{\"playing\":true}");
        assertEquals(RemoteCommand.Guard.Verdict.REPEAT, guard.check(101));
        assertEquals("{\"playing\":true}", guard.lastReply());
        assertEquals(RemoteCommand.Guard.Verdict.IGNORE, guard.check(100));
        assertEquals(RemoteCommand.Guard.Verdict.RUN, guard.check(102));
    }
}
