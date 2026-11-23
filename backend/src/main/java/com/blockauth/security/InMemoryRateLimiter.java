package com.blockauth.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window request counters and the temporary IP block for repeated authentication failures.
 * State lives in this JVM only: it is lost on restart and not shared between instances. It is the limiter
 * without Redis, and the fallback of {@link RedisRateLimiter} when Redis cannot be reached.
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

	private record Window(long endMillis, AtomicInteger count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();
	private final Clock clock;

	public InMemoryRateLimiter(Clock clock) {
		this.clock = clock;
	}

	@Override
	public Decision acquire(String bucket, String key, int limit, Duration window) {
		long now = clock.millis();
		Window current = windows.compute(bucket + '|' + key, (k, old) -> old == null || now >= old.endMillis()
				? new Window(now + window.toMillis(), new AtomicInteger()) : old);
		int count = current.count().incrementAndGet();
		if (count <= limit) {
			return ALLOWED;
		}
		return new Decision(false, RateLimiter.secondsUntil(current.endMillis(), now), count == limit + 1);
	}

	@Override
	public boolean recordAuthFailure(String ip) {
		Decision decision = acquire("auth-failure", ip, FAILURES_BEFORE_BLOCK - 1, FAILURE_WINDOW);
		if (decision.allowed() || !decision.firstRejection()) {
			return false;
		}
		blockedUntil.put(ip, clock.millis() + BLOCK_DURATION.toMillis());
		windows.remove("auth-failure|" + ip);
		return true;
	}

	@Override
	public long blockedSeconds(String ip) {
		Long until = blockedUntil.get(ip);
		long now = clock.millis();
		if (until == null) {
			return 0;
		}
		if (now >= until) {
			blockedUntil.remove(ip, until);
			return 0;
		}
		return RateLimiter.secondsUntil(until, now);
	}

	@Scheduled(fixedRate = 60_000)
	void sweep() {
		long now = clock.millis();
		windows.values().removeIf(w -> now >= w.endMillis());
		blockedUntil.values().removeIf(until -> now >= until);
	}

	@Override
	public void clear() {
		windows.clear();
		blockedUntil.clear();
	}

}
