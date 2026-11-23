package com.blockauth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;
import com.blockauth.blockchain.OnChainWalletStatus;
import com.blockauth.common.BlockAuthException;
import com.blockauth.common.ErrorCode;

/**
 * Wallet status changes the application did not make itself (attacks.md: "Database and chain drift",
 * "Timeout and partial failure"). Session checks read the database mirror only; the reconciler ends such
 * sessions once it reads the chain.
 */
class ChainStatusReconcilerIT extends IntegrationTest {

	@Autowired
	ChainStatusReconciler reconciler;

	private final TestWallet userWallet = new TestWallet();

	@Test
	void aRevocationMadeDirectlyOnTheChainEndsTheOpenSession() {
		Api user = registeredAndLoggedIn(userWallet);

		// The operator key revokes the wallet without going through the application.
		revokeOnChain(userWallet);
		// Nothing has read the chain yet: this is the window the reconciler closes.
		assertThat(user.get("/api/users/me").status()).isEqualTo(200);

		reconciler.reconcile();

		Api.Response next = user.get("/api/users/me");
		assertThat(next.status()).isEqualTo(401);
		assertThat(next.code()).isEqualTo("SESSION_REVOKED");
		assertThat(count("select count(*) from users where wallet_address = ? and status = 'REVOKED'",
				userWallet.address())).isEqualTo(1);
		assertThat(count("select count(*) from user_sessions where end_reason = 'WALLET_REVOKED'")).isEqualTo(1);
		assertThat(count("select count(*) from authentication_events where event_type = 'SESSION_TERMINATED'"))
				.isGreaterThanOrEqualTo(1);
		assertThat(login(browser(), userWallet).code()).isEqualTo("WALLET_REVOKED");
	}

	@Test
	void aRevocationMinedAfterTheTimeoutStillEndsTheSession() {
		Api admin = registeredAndLoggedIn(ADMIN);
		Api user = registeredAndLoggedIn(userWallet);
		// The transaction is mined, but only after the client stopped waiting for its receipt.
		doAnswer(call -> {
			revokeOnChain(userWallet);
			throw new BlockAuthException(ErrorCode.BLOCKCHAIN_TIMEOUT);
		}).when(registry).revokeWallet(anyString());

		Api.Response revoke = admin.post("/api/admin/wallet/" + userWallet.checksumAddress() + "/revoke",
				proof(admin, ADMIN, "REVOKE_WALLET", userWallet));

		assertThat(revoke.status()).isEqualTo(504);
		assertThat(revoke.code()).isEqualTo("BLOCKCHAIN_TIMEOUT");
		// The mirror still says active, so the session still works until the chain is read.
		assertThat(user.get("/api/users/me").status()).isEqualTo(200);

		reconciler.reconcile();

		assertThat(user.get("/api/users/me").code()).isEqualTo("SESSION_REVOKED");
		assertThat(count("select count(*) from users where wallet_address = ? and status = 'REVOKED'",
				userWallet.address())).isEqualTo(1);
	}

	@Test
	void aChainResetEndsTheSessionsOfWalletsItNoLongerKnows() {
		Api user = registeredAndLoggedIn(userWallet);

		chain.remove(userWallet.address());
		reconciler.reconcile();

		assertThat(user.get("/api/users/me").code()).isEqualTo("SESSION_REVOKED");
	}

	@Test
	void activeWalletsKeepTheirSessionsAndAnUnreachableChainChangesNothing() {
		Api active = registeredAndLoggedIn(userWallet);
		TestWallet revokedWallet = new TestWallet();
		Api revoked = registeredAndLoggedIn(revokedWallet);
		revokeOnChain(revokedWallet);
		doThrow(new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE)).when(registry).getWalletStatus(anyString());

		reconciler.reconcile();

		assertThat(active.get("/api/users/me").status()).isEqualTo(200);
		assertThat(revoked.get("/api/users/me").status()).isEqualTo(200);

		stubChain();
		reconciler.reconcile();

		assertThat(active.get("/api/users/me").status()).isEqualTo(200);
		assertThat(revoked.get("/api/users/me").code()).isEqualTo("SESSION_REVOKED");
	}

	private void revokeOnChain(TestWallet wallet) {
		chain.computeIfPresent(wallet.address(), (w, s) -> new OnChainWalletStatus(true, false, s.registeredAt()));
	}

}
