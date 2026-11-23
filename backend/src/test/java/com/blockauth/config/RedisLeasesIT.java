package com.blockauth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.blockauth.blockchain.ChainWriteLock;

/** Two "instances" of the leases against one Redis (REDIS_HOST/REDIS_PORT/REDIS_PASSWORD). Tagged "redis". */
@Tag("redis")
class RedisLeasesIT {

	private final java.util.List<LettuceConnectionFactory> factories = new java.util.ArrayList<>();

	private RedisLeases leases(int port) {
		RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
				System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"), port);
		String password = System.getenv("REDIS_PASSWORD");
		if (password != null && !password.isEmpty()) {
			config.setPassword(password);
		}
		LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
		factory.afterPropertiesSet();
		factory.start();
		factories.add(factory);
		return new RedisLeases(new StringRedisTemplate(factory), ChainTxSettings.defaults());
	}

	private static int port() {
		return Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
	}

	@AfterEach
	void close() {
		factories.forEach(LettuceConnectionFactory::destroy);
	}

	@Test
	void onlyOneInstanceAtATimeMayWriteToTheChain() {
		RedisLeases a = leases(port());
		RedisLeases b = leases(port());

		ChainWriteLock.Lease held = a.acquire(Duration.ofMillis(200));
		assertThat(held).isNotNull();
		long start = System.nanoTime();
		assertThat(b.acquire(Duration.ofMillis(300))).isNull();
		assertThat((System.nanoTime() - start) / 1_000_000).isBetween(250L, 3000L);
		held.close();
		try (ChainWriteLock.Lease next = b.acquire(Duration.ofMillis(300))) {
			assertThat(next).isNotNull();
		}
	}

	@Test
	void aScheduledJobRunsOnOneInstanceOnly() throws Exception {
		RedisLeases a = leases(port());
		RedisLeases b = leases(port());
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> a.runExclusive("test-job", Duration.ofMinutes(1),
				() -> {
					runs.incrementAndGet();
					inside.countDown();
					await(release);
				}));
		assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
		boolean second = b.runExclusive("test-job", Duration.ofMinutes(1), runs::incrementAndGet);
		release.countDown();

		assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
		assertThat(second).isFalse();
		assertThat(runs.get()).isEqualTo(1);
		assertThat(b.runExclusive("test-job", Duration.ofMinutes(1), runs::incrementAndGet)).isTrue();
	}

	@Test
	void withoutRedisNoLeaseIsGranted() {
		RedisLeases down = leases(1);

		assertThat(down.acquire(Duration.ofMillis(100))).isNull();
		assertThat(down.runExclusive("test-job", Duration.ofMinutes(1), () -> {
			throw new AssertionError("must not run");
		})).isFalse();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
