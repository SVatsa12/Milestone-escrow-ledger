package com.svatsa.escrow.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Clock tests can move. By default it tracks real time plus an offset, so code that measures
 * elapsed time still sees time pass, and {@link #advance(Duration)} jumps it ahead.
 *
 * {@link #freezeAt(Instant)} stops it: time then moves only through {@link #advance(Duration)}.
 * Use it when an assertion depends on where "now" falls, e.g. on a fixed rate-limit window: with
 * real time a test can straddle a window boundary and see its requests split over two windows.
 * {@link #reset()} (after every test) returns to real time with no offset.
 */
public class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<Instant> frozen = new AtomicReference<>();

    public void advance(Duration by) {
        if (frozen.getAndUpdate(f -> f == null ? null : f.plus(by)) == null) {
            offset.updateAndGet(current -> current.plus(by));
        }
    }

    /** Jump to {@code instant} (from then on, time keeps moving from there). */
    public void set(Instant instant) {
        frozen.set(null);
        offset.set(Duration.between(Instant.now(), instant));
    }

    /** Stop at {@code instant}: it stays there until {@link #advance}, {@link #set} or {@link #reset}. */
    public void freezeAt(Instant instant) {
        frozen.set(instant);
    }

    public void reset() {
        frozen.set(null);
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        Instant f = frozen.get();
        return f != null ? f : Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
