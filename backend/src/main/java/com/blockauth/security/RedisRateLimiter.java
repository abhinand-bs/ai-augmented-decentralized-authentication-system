package com.blockauth.security;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * The rate limits and IP block shared by every instance through Redis. Each window is one counter, incremented
 * and given its expiry atomically by a Lua script; {@code Retry-After} is the counter's remaining time to live.
 * If Redis cannot be reached the in-memory limiter of this instance takes over (logged at most once a minute):
 * limits then count per instance, but there is never a moment without limits.
 */
@Component
@Primary
@ConditionalOnProperty(name = "blockauth.redis.enabled", havingValue = "true")
public class RedisRateLimiter implements RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
	static final String PREFIX = "blockauth:rl:";

	// Returns {count, remaining milliseconds} of the window.
	private static final DefaultRedisScript<List> INCREMENT = new DefaultRedisScript<>("""
			local c = redis.call('INCR', KEYS[1])
			if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
			local ttl = redis.call('PTTL', KEYS[1])
			if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
			return {c, ttl}
			""", List.class);

	private final StringRedisTemplate redis;
	private final InMemoryRateLimiter fallback;
	private final AtomicLong lastWarning = new AtomicLong();

	public RedisRateLimiter(StringRedisTemplate redis, InMemoryRateLimiter fallback) {
		this.redis = redis;
		this.fallback = fallback;
	}

	@Override
	public Decision acquire(String bucket, String key, int limit, Duration window) {
		try {
			List<?> result = redis.execute(INCREMENT, List.of(PREFIX + bucket + ':' + key),
					String.valueOf(window.toMillis()));
			long count = ((Number) result.get(0)).longValue();
			long ttl = ((Number) result.get(1)).longValue();
			if (count <= limit) {
				return ALLOWED;
			}
			return new Decision(false, Math.max(1, (ttl + 999) / 1000), count == limit + 1);
		}
		catch (DataAccessException e) {
			warn(e);
			return fallback.acquire(bucket, key, limit, window);
		}
	}

	@Override
	public boolean recordAuthFailure(String ip) {
		Decision decision = acquire("auth-failure", ip, FAILURES_BEFORE_BLOCK - 1, FAILURE_WINDOW);
		if (decision.allowed() || !decision.firstRejection()) {
			return false;
		}
		try {
			redis.opsForValue().set(PREFIX + "block:" + ip, "1", BLOCK_DURATION);
			redis.delete(PREFIX + "auth-failure:" + ip);
		}
		catch (DataAccessException e) {
			warn(e);
			return fallback.recordAuthFailure(ip);
		}
		return true;
	}

	@Override
	public long blockedSeconds(String ip) {
		try {
			Long ttl = redis.getExpire(PREFIX + "block:" + ip, java.util.concurrent.TimeUnit.MILLISECONDS);
			return ttl == null || ttl <= 0 ? fallback.blockedSeconds(ip) : Math.max(1, (ttl + 999) / 1000);
		}
		catch (DataAccessException e) {
			warn(e);
			return fallback.blockedSeconds(ip);
		}
	}

	@Override
	public void clear() {
		fallback.clear();
		try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(PREFIX + "*").count(500).build())) {
			keys.forEachRemaining(redis::delete);
		}
		catch (DataAccessException e) {
			warn(e);
		}
	}

	private void warn(DataAccessException e) {
		long now = System.currentTimeMillis();
		long last = lastWarning.get();
		if (now - last > 60_000 && lastWarning.compareAndSet(last, now)) {
			log.warn("Redis unreachable for rate limits ({}): using this instance's own limits",
					e.getClass().getSimpleName());
		}
	}

}
