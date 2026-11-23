package com.blockauth.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;
import com.blockauth.audit.ClientInfo;
import com.blockauth.common.BlockAuthException;
import com.blockauth.secondstep.SecondStepService;
import com.blockauth.secondstep.SecondStepTestSupport;

/**
 * attacks.md "Concurrent single-use race": 50 simultaneous uses of one challenge, one backup code and one
 * authenticator code. Exactly one may succeed. Run against the services directly, because over HTTP from
 * one test address the per-address rate limit would refuse most of the 50 before they reached the check.
 */
class SingleUseRaceIT extends IntegrationTest {

	private static final int RACERS = 50;

	@Autowired
	ChallengeService challenges;

	@Autowired
	SecondStepService secondStep;

	@Test
	void fiftySimultaneousUsesOfOneChallengeConsumeItOnce() throws Exception {
		TestWallet wallet = new TestWallet();
		AuthenticationChallenge issued = challenges.issue(wallet.address(), ChallengePurpose.LOGIN,
				new ClientInfo("127.0.0.1", null));

		List<Object> outcomes = race(() -> {
			try {
				challenges.consume(issued.getChallenge(), wallet.address(), ChallengePurpose.LOGIN);
				return "CONSUMED";
			}
			catch (BlockAuthException e) {
				return e.code().name();
			}
		});

		assertThat(outcomes).filteredOn("CONSUMED"::equals).hasSize(1);
		assertThat(outcomes).filteredOn("CHALLENGE_ALREADY_USED"::equals).hasSize(RACERS - 1);
	}

	@Test
	void fiftySimultaneousUsesOfOneBackupCodeOrOneAuthenticatorCodeSucceedOnce() throws Exception {
		TestWallet wallet = new TestWallet();
		Api api = registeredAndLoggedIn(wallet);
		SecondStepTestSupport.Enabled enabled = SecondStepTestSupport.enable(api, clock.instant(),
				action -> proof(api, wallet, action, Map.of()));
		UUID userId = jdbc.queryForObject("select id from users where wallet_address = ?", UUID.class,
				wallet.address());

		String backupCode = enabled.backupCodes().get(0);
		List<Object> backup = race(() -> secondStep.verifyCode(userId, backupCode));

		// The next 30-second step: the code used to turn the second step on was the previous one.
		clock.advance(Duration.ofSeconds(30));
		String code = SecondStepTestSupport.code(enabled.secret(), clock.instant());
		List<Object> totp = race(() -> secondStep.verifyCode(userId, code));

		assertThat(backup).filteredOn(Boolean.TRUE::equals).hasSize(1);
		assertThat(totp).filteredOn(Boolean.TRUE::equals).hasSize(1);
		assertThat(count("select count(*) from backup_codes where used_at is not null")).isEqualTo(1);
	}

	/** Starts {@link #RACERS} calls at the same moment on their own threads and returns every result. */
	private static List<Object> race(Callable<Object> call) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(RACERS);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Object>> running = new ArrayList<>();
			for (int i = 0; i < RACERS; i++) {
				running.add(pool.submit(() -> {
					start.await();
					return call.call();
				}));
			}
			start.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> future : running) {
				results.add(future.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
