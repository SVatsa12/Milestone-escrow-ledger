package com.svatsa.provider.mode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The current mode, in memory only: it defaults to SUCCESS and resets on restart.
 *
 * Every random choice (RANDOM's pick, ASYNC's delay and outcome) comes from one RNG seeded by
 * {@code seed}, re-created on every mode change. The same seed and the same sequence of requests
 * therefore give the same behaviour. All methods are synchronized; concurrent requests still get
 * a well-defined (if interleaved) sequence.
 */
@Component
public class ModeState {

    private static final Logger log = LoggerFactory.getLogger(ModeState.class);

    public static final long DEFAULT_DELAY_MS = 3_000;

    /** RANDOM's default weights: mostly successes, every failure kind represented. */
    public static final Map<Mode, Integer> DEFAULT_WEIGHTS = defaultWeights();

    private ModeConfig config;
    private Random rng;

    public ModeState() {
        apply(new ModeConfig(Mode.SUCCESS, DEFAULT_DELAY_MS, DEFAULT_WEIGHTS, null));
    }

    public synchronized ModeConfig current() {
        return config;
    }

    /**
     * Replace the mode. Missing fields fall back to the defaults; a missing seed gets a random one,
     * reported back so the run can be reproduced. Throws IllegalArgumentException on bad input.
     */
    public synchronized ModeConfig set(ModeConfig requested) {
        if (requested == null || requested.mode() == null) {
            throw new IllegalArgumentException("mode is required");
        }
        ModeConfig previous = config;
        apply(requested);
        log.info("provider.mode-changed from={} to={} delayMs={} weights={} seed={}",
            previous.mode(), config.mode(), config.delayMs(), config.weights(), config.seed());
        return config;
    }

    /** The concrete mode for one request: RANDOM is resolved here. */
    public synchronized Mode next() {
        if (config.mode() != Mode.RANDOM) {
            return config.mode();
        }
        int total = config.weights().values().stream().mapToInt(Integer::intValue).sum();
        int roll = rng.nextInt(total);
        for (Map.Entry<Mode, Integer> w : config.weights().entrySet()) {   // EnumMap: fixed order
            roll -= w.getValue();
            if (roll < 0) {
                return w.getKey();
            }
        }
        throw new IllegalStateException("unreachable: weights sum to " + total);
    }

    /** ASYNC: how long until the scheduler settles the payout, 5 to 15 seconds. */
    public synchronized long nextAsyncDelayMs() {
        return 5_000 + rng.nextInt(10_001);
    }

    /** ASYNC: SUCCESS 90% of the time, FAILED otherwise. */
    public synchronized boolean nextAsyncSucceeds() {
        return rng.nextInt(100) < 90;
    }

    private void apply(ModeConfig requested) {
        long delayMs = requested.delayMs() == null ? DEFAULT_DELAY_MS : requested.delayMs();
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0");
        }
        Map<Mode, Integer> weights = new EnumMap<>(Mode.class);
        if (requested.weights() == null || requested.weights().isEmpty()) {
            weights.putAll(DEFAULT_WEIGHTS);
        } else {
            for (Map.Entry<Mode, Integer> w : requested.weights().entrySet()) {
                if (w.getKey() == Mode.RANDOM) {
                    throw new IllegalArgumentException("RANDOM can't be one of RANDOM's weights");
                }
                if (w.getValue() == null || w.getValue() < 0) {
                    throw new IllegalArgumentException("weights must be >= 0");
                }
                if (w.getValue() > 0) {
                    weights.put(w.getKey(), w.getValue());
                }
            }
            if (weights.isEmpty()) {
                throw new IllegalArgumentException("at least one weight must be > 0");
            }
        }
        long seed = requested.seed() == null ? ThreadLocalRandom.current().nextLong() : requested.seed();
        // Unmodifiable EnumMap, not Map.copyOf: next() relies on the fixed enum iteration order
        // (Map.copyOf iterates in a per-JVM random order, which would break seeded runs).
        this.config = new ModeConfig(requested.mode(), delayMs, Collections.unmodifiableMap(weights), seed);
        this.rng = new Random(seed);
    }

    private static Map<Mode, Integer> defaultWeights() {
        Map<Mode, Integer> w = new EnumMap<>(Mode.class);
        w.put(Mode.SUCCESS, 50);
        w.put(Mode.FAIL, 10);
        w.put(Mode.ERROR_500, 10);
        w.put(Mode.TIMEOUT_THEN_SUCCEED, 10);
        w.put(Mode.TIMEOUT_THEN_FAIL, 5);
        w.put(Mode.TIMEOUT_NOT_RECEIVED, 5);
        w.put(Mode.ASYNC, 10);
        return Collections.unmodifiableMap(w);
    }
}
