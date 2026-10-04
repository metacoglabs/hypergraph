package io.hstore.server.studio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginThrottleTest {

    private final AtomicLong now = new AtomicLong();
    private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(60), now::get);

    @Test
    void blocksAnAddressAfterTheLimitUntilTheWindowEnds() {
        for (int i = 0; i < 3; i++) {
            assertEquals(0, throttle.secondsUntilAllowed("10.0.0.1"));
            throttle.failed("10.0.0.1");
        }
        assertEquals(60, throttle.secondsUntilAllowed("10.0.0.1"));
        assertEquals(0, throttle.secondsUntilAllowed("10.0.0.2"));
        now.addAndGet(Duration.ofSeconds(45).toNanos());
        assertEquals(15, throttle.secondsUntilAllowed("10.0.0.1"));
        now.addAndGet(Duration.ofSeconds(15).toNanos());
        assertEquals(0, throttle.secondsUntilAllowed("10.0.0.1"));
    }

    @Test
    void successResetsTheCount() {
        throttle.failed("10.0.0.1");
        throttle.failed("10.0.0.1");
        throttle.succeeded("10.0.0.1");
        throttle.failed("10.0.0.1");
        throttle.failed("10.0.0.1");
        assertEquals(0, throttle.secondsUntilAllowed("10.0.0.1"));
        throttle.failed("10.0.0.1");
        assertTrue(throttle.secondsUntilAllowed("10.0.0.1") > 0);
    }
}
