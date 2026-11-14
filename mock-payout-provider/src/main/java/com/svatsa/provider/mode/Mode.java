package com.svatsa.provider.mode;

/**
 * How the provider answers the next POST /v1/payouts. RANDOM picks one of the others per request
 * using the configured weights.
 */
public enum Mode {
    /** Persist SUCCESS, 200. */
    SUCCESS,
    /** Persist FAILED (INVALID_ACCOUNT), 422. */
    FAIL,
    /** Persist nothing, 500. */
    ERROR_500,
    /** Persist SUCCESS, sleep delayMs, then 200: the bank paid but the answer is late. */
    TIMEOUT_THEN_SUCCEED,
    /** Persist FAILED, sleep delayMs, then 422. */
    TIMEOUT_THEN_FAIL,
    /** Sleep delayMs, persist nothing, 504: the request was "lost". */
    TIMEOUT_NOT_RECEIVED,
    /** Persist PROCESSING, 202; the scheduler settles it 5–15s later (90% SUCCESS). */
    ASYNC,
    /** Pick one of the above per request by weight (seedable). */
    RANDOM
}
