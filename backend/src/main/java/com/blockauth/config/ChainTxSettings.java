package com.blockauth.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * How the backend sends its registry transactions. The defaults suit the local Hardhat node; a public test
 * network such as Sepolia needs a longer receipt timeout (see .env.example and docs/SEPOLIA.md).
 *
 * @param receiptTimeout how long a request waits for the transaction to be mined and confirmed
 * @param confirmations blocks that must exist on top of (and including) the transaction's block
 * @param lockWait how long a request waits for another chain write of this instance (and, with Redis, of any
 * instance) to finish before it is refused with 503
 * @param gasLimitCap upper bound on the gas limit of one registry transaction
 * @param maxFeeGwei upper bound on the fee per gas the backend will pay; above it the write is refused
 * @param lowBalanceEth below this operator balance a warning is logged and shown to administrators
 */
@ConfigurationProperties("blockauth.blockchain.tx")
@Validated
public record ChainTxSettings(
		@DefaultValue("30s") @NotNull Duration receiptTimeout,
		@DefaultValue("1") @Min(1) @Max(64) int confirmations,
		@DefaultValue("10s") @NotNull Duration lockWait,
		@DefaultValue("200000") @Min(21000) long gasLimitCap,
		@DefaultValue("200") @Min(1) long maxFeeGwei,
		@DefaultValue("0.05") @NotNull BigDecimal lowBalanceEth) {

	public static ChainTxSettings defaults() {
		return new ChainTxSettings(Duration.ofSeconds(30), 1, Duration.ofSeconds(10), 200_000, 200,
				new BigDecimal("0.05"));
	}

}
