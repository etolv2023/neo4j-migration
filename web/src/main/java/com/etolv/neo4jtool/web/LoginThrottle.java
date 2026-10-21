package com.etolv.neo4jtool.web;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Too many wrong second-step codes in a short time lock that user's second step for a while. Kept in memory. */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<String, Deque<Long>> failures = new HashMap<>();

    @Autowired
    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** @return how long until the next try is allowed; zero when it is allowed now */
    public synchronized Duration lockedFor(String user) {
        Deque<Long> times = recent(user);
        if (times.size() < MAX_FAILURES) {
            return Duration.ZERO;
        }
        long freeAt = times.peekFirst() + WINDOW.toMillis();
        return Duration.ofMillis(Math.max(0, freeAt - clock.millis()));
    }

    public synchronized void failed(String user) {
        recent(user).addLast(clock.millis());
    }

    public synchronized void succeeded(String user) {
        failures.remove(user);
    }

    private Deque<Long> recent(String user) {
        Deque<Long> times = failures.computeIfAbsent(user, k -> new ArrayDeque<>());
        long limit = clock.millis() - WINDOW.toMillis();
        while (!times.isEmpty() && times.peekFirst() <= limit) {
            times.removeFirst();
        }
        return times;
    }
}
