package com.blockauth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;
import com.blockauth.common.BlockAuthException;
import com.blockauth.common.ErrorCode;

/**
 * A stolen operator key used directly on the contract. The contract makes every reactivation and unpause
 * wait; the guard cancels any such request that BlockAuth did not make, and keeps the contract paused while
 * the emergency stop is on.
 */
class ChainRequestGuardIT extends IntegrationTest {

	private static final Duration AFTER_DELAY = CHAIN_DELAY.plusSeconds(1);

	@Autowired
	private ChainRequestGuard guard;

	@Autowired
	private AdminActionService actions;

	private final TestWallet user = new TestWallet();
	private Api admin;

	@BeforeEach
	void setUp() {
		admin = registeredAndLoggedIn(ADMIN);
		assertThat(register(browser(), user).status()).isEqualTo(201);
		assertThat(admin.post("/api/admin/wallet/" + user.checksumAddress() + "/revoke",
				proof(admin, ADMIN, "REVOKE_WALLET", user)).status()).isEqualTo(200);
	}

	private long unexpected() {
		return count("select count(*) from security_events where attack_type = 'UNEXPECTED_CHAIN_REQUEST'");
	}

	@Test
	void aReactivationRequestedBehindBlockAuthsBackIsCancelled() {
		// The thief calls the contract directly with the operator key.
		registry.requestActivation(user.address());
		assertThat(chainActivations).containsKey(user.address());

		guard.check();

		assertThat(chainActivations).isEmpty();
		assertThat(unexpected()).isEqualTo(1);
		clock.advance(AFTER_DELAY);
		assertThat(chain.get(user.address()).active()).isFalse();
		assertThat(login(browser(), user).code()).isEqualTo("WALLET_REVOKED");
	}

	@Test
	void aReactivationRequestedThroughBlockAuthIsLeftToRunItsCourse() {
		assertThat(admin.post("/api/admin/wallet/" + user.checksumAddress() + "/activate",
				proof(admin, ADMIN, "ACTIVATE_WALLET", user)).status()).isEqualTo(202);
		assertThat(chainActivations).containsKey(user.address());

		guard.check();

		assertThat(chainActivations).containsKey(user.address());
		assertThat(unexpected()).isZero();
		clock.advance(AFTER_DELAY);
		actions.runDue();
		assertThat(chain.get(user.address()).active()).isTrue();
	}

	@Test
	void aRequestLeftOnTheChainByACancelThatCouldNotReachItIsCancelledLater() {
		long id = admin.post("/api/admin/wallet/" + user.checksumAddress() + "/activate",
				proof(admin, ADMIN, "ACTIVATE_WALLET", user)).body().get("id").asLong();
		doThrow(new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE)).when(registry).cancelActivation(anyString());

		Api.Response cancelled = admin.post("/api/admin/actions/" + id + "/cancel",
				proof(admin, ADMIN, "CANCEL_ADMIN_ACTION", Map.of("actionId", id)));

		assertThat(cancelled.body().get("status").asString()).isEqualTo("CANCELLED");
		assertThat(chainActivations).containsKey(user.address());
		stubChain();
		guard.check();
		assertThat(chainActivations).isEmpty();
	}

	@Test
	void theEmergencyStopPausesTheContractAndAnUnpauseNobodyRequestedIsCancelled() {
		Map<String, Object> body = new LinkedHashMap<>(proof(admin, ADMIN, "START_EMERGENCY_STOP",
				Map.of("blockLogins", false, "endUserSessions", false)));
		body.putAll(Map.of("reason", "Operator key exposed", "blockLogins", false, "endUserSessions", false));
		assertThat(admin.post("/api/admin/emergency-stop/activate", body).status()).isEqualTo(200);
		assertThat(chainPaused).isTrue();

		// The thief cannot register or reactivate on the chain, and asks for an unpause.
		assertThat(chainActivations).isEmpty();
		registry.requestUnpause();
		guard.check();
		assertThat(chainUnpauseAt.get()).isNull();
		assertThat(unexpected()).isEqualTo(1);

		// If the contract ever ends up unpaused while the stop is on, the guard pauses it again.
		chainPaused.set(false);
		guard.check();
		assertThat(chainPaused).isTrue();

		// Lifting the stop through BlockAuth waits on both sides and then unpauses the contract.
		long id = admin.post("/api/admin/emergency-stop/lift", proof(admin, ADMIN, "LIFT_EMERGENCY_STOP", Map.of()))
			.body().get("id").asLong();
		assertThat(chainUnpauseAt.get()).isNotNull();
		guard.check();
		assertThat(chainUnpauseAt.get()).isNotNull();
		clock.advance(AFTER_DELAY);
		actions.runDue();
		assertThat(jdbc.queryForObject("select status from admin_actions where id = ?", String.class, id))
			.isEqualTo("EXECUTED");
		assertThat(chainPaused).isFalse();
		assertThat(admin.get("/api/admin/emergency-stop").body().get("active").asBoolean()).isFalse();
	}

	@Test
	void aReactivationCancelledOnTheChainByTheGuardianFailsInsteadOfWaitingForever() {
		long id = admin.post("/api/admin/wallet/" + user.checksumAddress() + "/activate",
				proof(admin, ADMIN, "ACTIVATE_WALLET", user)).body().get("id").asLong();
		// The guardian cancels it on the chain, outside BlockAuth.
		chainActivations.clear();

		clock.advance(AFTER_DELAY);
		actions.runDue();

		assertThat(jdbc.queryForObject("select status from admin_actions where id = ?", String.class, id))
			.isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select failure_reason from admin_actions where id = ?", String.class, id))
			.isEqualTo("CHAIN_REQUEST_MISSING");
		assertThat(chain.get(user.address()).active()).isFalse();
	}

}
