package com.blockauth.blockchain;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.blockauth.common.WalletAddresses;
import com.blockauth.user.UserService;
import org.web3j.utils.Convert;

/** ADMIN-only lookups of any wallet's on-chain identity status, and the operator account's health. */
@RestController
@RequestMapping("/api/blockchain")
public class BlockchainController {

	public record VerifyResponse(String walletAddress, boolean valid, String reason) {
	}

	/** The operator account pays for every registry transaction; a low balance warns before writes start failing. */
	public record OperatorResponse(String operatorAddress, String balanceEth, boolean balanceLow) {
	}

	private final UserService users;
	private final WalletRegistryClient registry;

	public BlockchainController(UserService users, WalletRegistryClient registry) {
		this.users = users;
		this.registry = registry;
	}

	@GetMapping("/operator")
	OperatorResponse operator() {
		BigDecimal eth = Convert.fromWei(new BigDecimal(registry.operatorBalanceWei()), Convert.Unit.ETHER);
		return new OperatorResponse(WalletAddresses.checksum(registry.operatorAddress()),
				eth.stripTrailingZeros().toPlainString(), registry.operatorBalanceLow());
	}

	@GetMapping("/wallet/{address}")
	WalletStatusResponse wallet(@PathVariable String address) {
		String wallet = WalletAddresses.normalize(address, "address");
		return WalletStatusResponse.of(wallet, users.chainStatus(wallet));
	}

	@GetMapping("/verify/{address}")
	VerifyResponse verify(@PathVariable String address) {
		String wallet = WalletAddresses.normalize(address, "address");
		OnChainWalletStatus status = users.chainStatus(wallet);
		return new VerifyResponse(WalletAddresses.checksum(wallet), status.valid(), status.label());
	}

}
