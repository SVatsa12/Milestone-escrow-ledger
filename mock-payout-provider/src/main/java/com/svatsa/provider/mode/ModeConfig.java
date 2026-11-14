package com.svatsa.provider.mode;

import java.util.Map;

/**
 * Body of POST /admin/mode and GET /admin/mode. Only {@code mode} is required.
 *
 * @param delayMs how long the TIMEOUT_* modes sleep (default 3000)
 * @param weights RANDOM's relative weights per concrete mode (default {@link ModeState#DEFAULT_WEIGHTS})
 * @param seed    seeds every random choice; GET reports the one in use so a run can be repeated
 */
public record ModeConfig(Mode mode, Long delayMs, Map<Mode, Integer> weights, Long seed) {
}
