package com.blockauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Load smoke test, not a benchmark: 50 sign-ins at the same moment through the real HTTP stack and database
 * (chain stand-in as in every IT). Records latency, peak pool use and thread count; asserts only that every
 * sign-in succeeds and the pool is never exhausted. Tagged "load": run with -Dit.test=LoadSmokeIT
 * -Dit.excludedGroups= . The 50 clients come from 50 addresses, reported by a trusted proxy as in production
 * (one address would rightly be limited to 10 sign-ins a minute).
 */
@Tag("load")
@TestPropertySource(properties = { "server.forward-headers-strategy=native",
		"server.tomcat.remoteip.internal-proxies=127.0.0.1/32, ::1/128" })
class LoadSmokeIT extends IntegrationTest {

	private static final int LOGINS = 50;

	@Autowired
	private DataSource dataSource;

	@Test
	void fiftyConcurrentLogins() throws Exception {
		List<TestWallet> wallets = new ArrayList<>();
		for (int i = 0; i < LOGINS; i++) {
			if (i % 4 == 0) {
				rateLimiter.clear(); // setup only: registrations from one address would hit the per-IP limit
			}
			TestWallet wallet = new TestWallet();
			assertThat(register(browser(), wallet).status()).isEqualTo(201);
			wallets.add(wallet);
		}
		List<Api> browsers = new ArrayList<>();
		List<String> challenges = new ArrayList<>();
		for (int i = 0; i < LOGINS; i++) {
			if (i % 4 == 0) {
				rateLimiter.clear();
			}
			Api api = browser();
			browsers.add(api);
			challenges.add(challenge(api, wallets.get(i), "/api/auth/challenge"));
		}
		rateLimiter.clear();

		var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
		AtomicBoolean running = new AtomicBoolean(true);
		AtomicInteger peakActive = new AtomicInteger();
		AtomicInteger peakWaiting = new AtomicInteger();
		CompletableFuture<Void> sampler = CompletableFuture.runAsync(() -> {
			while (running.get()) {
				peakActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
				peakWaiting.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
				Thread.onSpinWait();
			}
		});
		int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
		long[] millis = new long[LOGINS];
		List<Supplier<Api.Response>> calls = new ArrayList<>();
		for (int i = 0; i < LOGINS; i++) {
			int n = i;
			calls.add(() -> {
				long start = System.nanoTime();
				Api.Response r = browsers.get(n).send("POST", "/api/auth/verify",
						Api.json(signed(wallets.get(n), challenges.get(n), wallets.get(n))),
						java.util.Map.of("X-Forwarded-For", "198.51.100." + (n + 1)), true);
				millis[n] = (System.nanoTime() - start) / 1_000_000;
				return r;
			});
		}
		long wallStart = System.nanoTime();
		List<Api.Response> responses = concurrently(calls);
		long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;
		int peakThreads = ManagementFactory.getThreadMXBean().getPeakThreadCount();
		running.set(false);
		sampler.get();

		long[] sorted = millis.clone();
		Arrays.sort(sorted);
		System.out.printf("Load smoke: %d concurrent logins in %d ms; latency p50 %d ms, p95 %d ms, max %d ms; "
				+ "pool max %d, peak active %d, peak waiting %d; threads %d before, JVM peak %d%n", LOGINS, wallMillis,
				sorted[LOGINS / 2], sorted[(int) (LOGINS * 0.95)], sorted[LOGINS - 1],
				((HikariDataSource) dataSource).getMaximumPoolSize(), peakActive.get(), peakWaiting.get(), threadsBefore,
				peakThreads);
		assertThat(responses).allSatisfy(r -> assertThat(r.status()).as(r.body().toString()).isEqualTo(200));
		assertThat(peakActive.get()).isLessThanOrEqualTo(((HikariDataSource) dataSource).getMaximumPoolSize());
		assertThat(count("select count(*) from authentication_events where event_type = 'LOGIN' "
				+ "and event_status = 'SUCCESS'")).isEqualTo(LOGINS);
	}

}
