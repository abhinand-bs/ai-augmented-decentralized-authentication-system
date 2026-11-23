package com.blockauth.config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.blockauth.blockchain.ChainWriteLock;
import com.blockauth.common.JobLock;

/**
 * Short leases in Redis (SET NX with an expiry, released only by their holder) for the two things only one
 * instance may do at a time: send an operator transaction (or two instances would sign with the same nonce) and
 * run a scheduled job. When Redis cannot be reached no lease is granted: a chain write is refused with 503 and
 * the job skips this run; nothing runs twice.
 */
@Component
@ConditionalOnProperty(name = "blockauth.redis.enabled", havingValue = "true")
public class RedisLeases implements ChainWriteLock, JobLock {

	private static final Logger log = LoggerFactory.getLogger(RedisLeases.class);
	private static final String PREFIX = "blockauth:lease:";
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
			"if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
			Long.class);

	private final StringRedisTemplate redis;
	private final Duration chainLeaseTtl;

	public RedisLeases(StringRedisTemplate redis, ChainTxSettings tx) {
		this.redis = redis;
		// Longer than any write can take (receipt timeout plus a margin), so a lease is never lost mid-write.
		this.chainLeaseTtl = tx.receiptTimeout().plusSeconds(60);
	}

	@Override
	public Lease acquire(Duration wait) {
		long deadline = System.nanoTime() + wait.toNanos();
		String token = UUID.randomUUID().toString();
		try {
			while (true) {
				if (tryLease("chain-write", token, chainLeaseTtl)) {
					return () -> release("chain-write", token);
				}
				if (System.nanoTime() >= deadline) {
					return null;
				}
				Thread.sleep(50);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		catch (DataAccessException e) {
			log.warn("Redis unreachable: chain write refused ({})", e.getClass().getSimpleName());
			return null;
		}
	}

	@Override
	public boolean runExclusive(String job, Duration ttl, Runnable task) {
		String token = UUID.randomUUID().toString();
		try {
			if (!tryLease("job:" + job, token, ttl)) {
				return false;
			}
		}
		catch (DataAccessException e) {
			log.warn("Redis unreachable: job {} skipped this run ({})", job, e.getClass().getSimpleName());
			return false;
		}
		try {
			task.run();
			return true;
		}
		finally {
			release("job:" + job, token);
		}
	}

	private boolean tryLease(String name, String token, Duration ttl) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + name, token, ttl));
	}

	private void release(String name, String token) {
		try {
			redis.execute(RELEASE, List.of(PREFIX + name), token);
		}
		catch (DataAccessException e) {
			// The lease expires on its own.
		}
	}

}
