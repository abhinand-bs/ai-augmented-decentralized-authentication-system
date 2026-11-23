package com.blockauth.blockchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/**
 * The type-2 encoding must match what Ethereum clients expect. The expected values were produced by ethers v6
 * ({@code Wallet.signTransaction}) for the same key and fields (see docs/IMPLEMENTATION_LOG.md); signatures are
 * deterministic (RFC 6979), so the bytes must match exactly.
 */
class Eip1559TransactionTest {

	// Derived at runtime, so no key-shaped literal sits in the repository: keccak256("blockauth eip-1559 test vector").
	private static final Credentials KEY = Credentials.create(Numeric.toHexString(
			Hash.sha3("blockauth eip-1559 test vector".getBytes(StandardCharsets.UTF_8))));

	@Test
	void signsExactlyLikeEthers() {
		Eip1559Transaction tx = new Eip1559Transaction(11155111, BigInteger.valueOf(7), BigInteger.valueOf(1_500_000_000L),
				BigInteger.valueOf(30_000_000_000L), BigInteger.valueOf(120_000), "0x5FbDB2315678afecb367f032d93F642f64180aa3",
				BigInteger.ZERO, "0x5d6d4ffc000000000000000000000000000000000000000000000000000000000000abcd");

		String signed = tx.sign(KEY);

		assertThat(KEY.getAddress()).isEqualToIgnoringCase("0x3bb55F4cC7B236D75faAb2F6e353148DFef01aC0");
		assertThat(signed).isEqualTo("0x02f89383aa36a7078459682f008506fc23ac008301d4c0945fbdb2315678afecb367f032d9"
				+ "3f642f64180aa380a45d6d4ffc000000000000000000000000000000000000000000000000000000000000abcdc080a0eec"
				+ "eaec49312229d1e003a194a569a583666c5ebf25a9b651a06fdcafe792062a05c7ad49273ce769fbfe4f182572af10d5b728"
				+ "a770478531bed2e51f51a8fe6b2");
		assertThat(Eip1559Transaction.hashOf(signed))
			.isEqualTo("0x74d87c3c057093f1b346ed356637da535cbeaf3ba4f96afe9efc3b406b6fcc0e");
	}

}
