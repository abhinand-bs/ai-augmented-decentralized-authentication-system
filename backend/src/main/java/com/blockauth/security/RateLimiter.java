package com.blockauth.security;

import java.time.Duration;

/**
 * Fixed-window request counters and the temporary IP block after repeated authentication failures.
 * {@link InMemoryRateLimiter} keeps them in this JVM (the default, and the fallback); {@link RedisRateLimiter}
 * shares them between instances. There is never a mode without limits.
 */
public interface RateLimiter {

	int FAILURES_BEFORE_BLOCK = 10;
	Duration FAILURE_WINDOW = Duration.ofMinutes(15);
	Duration BLOCK_DURATION = Duration.ofMinutes(15);

	record Decision(boolean allowed, long retryAfterSeconds, boolean firstRejection) {
	}

	Decision ALLOWED = new Decision(true, 0, false);

	/** Counts one request against {@code bucket}/{@code key}; rejected once {@code limit} is exceeded. */
	Decision acquire(String bucket, String key, int limit, Duration window);

	/** @return true when this failure is the one that starts a block for the IP */
	boolean recordAuthFailure(String ip);

	/** @return seconds the IP remains blocked from authentication endpoints, or 0 when not blocked */
	long blockedSeconds(String ip);

	/** Forgets every counter and block (tests, and an operator's reset). */
	void clear();

	static long secondsUntil(long endMillis, long nowMillis) {
		return Math.max(1, (endMillis - nowMillis + 999) / 1000);
	}

}
