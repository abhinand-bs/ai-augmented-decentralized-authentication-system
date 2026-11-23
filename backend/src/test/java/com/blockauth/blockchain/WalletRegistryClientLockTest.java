package com.blockauth.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;

import com.blockauth.common.BlockAuthException;
import com.blockauth.common.ErrorCode;
import com.blockauth.config.BlockAuthProperties;
import com.blockauth.config.ChainTxSettings;

/**
 * Audit finding A-04: chain writes are serialized, but a request waiting for its turn must give up after
 * lockWait with a clean 503 (BLOCKCHAIN_BUSY) instead of holding its thread for as long as the others take.
 */
class WalletRegistryClientLockTest {

	private static final String WALLET = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";

	@Test
	void aSecondWriteGivesUpAfterTheLockWait() throws Exception {
		Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
		CountDownLatch firstInside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(web3j.ethEstimateGas(any()).send()).thenAnswer(call -> {
			firstInside.countDown();
			release.await(10, TimeUnit.SECONDS);
			throw new java.io.IOException("node gone"); // ends the first write; its outcome is not the point here
		});
		BlockAuthProperties properties = new BlockAuthProperties("http://localhost:5173", 120, List.of(),
				new BlockAuthProperties.Blockchain("http://127.0.0.1:1", 31337,
						"0x5FbDB2315678afecb367f032d93F642f64180aa3", "0x" + "22".repeat(32)));
		ChainTxSettings settings = new ChainTxSettings(Duration.ofSeconds(1), 1, Duration.ofMillis(300), 200_000, 200,
				BigDecimal.ONE);
		WalletRegistryClient client = new WalletRegistryClient(web3j, properties, settings, settings.receiptTimeout(),
				ChainWriteLock.NONE);

		CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(() -> failureOf(() -> client.revokeWallet(WALLET)));
		assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();
		long start = System.nanoTime();
		Throwable second = failureOf(() -> client.registerWallet(WALLET));
		long waitedMillis = (System.nanoTime() - start) / 1_000_000;
		release.countDown();

		assertThat(second).isInstanceOf(BlockAuthException.class);
		assertThat(((BlockAuthException) second).code()).isEqualTo(ErrorCode.BLOCKCHAIN_BUSY);
		assertThat(ErrorCode.BLOCKCHAIN_BUSY.status()).isEqualTo(503);
		assertThat(waitedMillis).isBetween(250L, 3000L);
		assertThat(((BlockAuthException) first.get(5, TimeUnit.SECONDS)).code()).isEqualTo(ErrorCode.BLOCKCHAIN_UNAVAILABLE);
	}

	@Test
	void aDistributedLeaseThatCannotBeHadIsBusyToo() {
		Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);
		BlockAuthProperties properties = new BlockAuthProperties("http://localhost:5173", 120, List.of(),
				new BlockAuthProperties.Blockchain("http://127.0.0.1:1", 31337,
						"0x5FbDB2315678afecb367f032d93F642f64180aa3", "0x" + "22".repeat(32)));
		ChainTxSettings settings = ChainTxSettings.defaults();
		WalletRegistryClient client = new WalletRegistryClient(web3j, properties, settings, settings.receiptTimeout(),
				wait -> null);

		Throwable failure = failureOf(() -> client.revokeWallet(WALLET));

		assertThat(((BlockAuthException) failure).code()).isEqualTo(ErrorCode.BLOCKCHAIN_BUSY);
	}

	private static Throwable failureOf(Runnable call) {
		try {
			call.run();
			return null;
		}
		catch (RuntimeException e) {
			return e;
		}
	}

}
