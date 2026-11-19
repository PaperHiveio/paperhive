package org.abdullahcxd.paperhive.bridge.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CooldownsTest {

    private final AtomicLong now = new AtomicLong();
    private final Cooldowns<String> cooldowns = new Cooldowns<>(Duration.ofSeconds(10), now::get);

    @Test
    void usingStartsTheCooldownUntilItRunsOut() {
        assertTrue(cooldowns.tryUse("steve"));
        assertFalse(cooldowns.tryUse("steve"));
        assertTrue(cooldowns.tryUse("alex"), "per key");

        now.addAndGet(Duration.ofSeconds(4).toNanos());
        assertEquals(Duration.ofSeconds(6), cooldowns.remaining("steve"));

        now.addAndGet(Duration.ofSeconds(6).toNanos());
        assertEquals(Duration.ZERO, cooldowns.remaining("steve"));
        assertTrue(cooldowns.tryUse("steve"));
    }

    @Test
    void resetEndsItNow() {
        cooldowns.tryUse("steve");
        cooldowns.reset("steve");
        assertTrue(cooldowns.tryUse("steve"));
    }

}
