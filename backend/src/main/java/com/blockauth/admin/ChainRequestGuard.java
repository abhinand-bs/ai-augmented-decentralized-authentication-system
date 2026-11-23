package com.blockauth.admin;

import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.blockauth.audit.AttackType;
import com.blockauth.audit.AuditService;
import com.blockauth.blockchain.WalletRegistryClient;
import com.blockauth.common.BlockAuthException;
import com.blockauth.common.WalletAddresses;

/**
 * Watches the contract for requests BlockAuth did not make. Whoever holds the operator key can ask the
 * contract to reactivate a wallet or to lift a pause; the contract makes that wait. Every 15 seconds by
 * default this cancels any such waiting request that has no matching waiting action in BlockAuth, records
 * it as UNEXPECTED_CHAIN_REQUEST, and pauses the contract again if the emergency stop is on in BlockAuth.
 * <p>
 * It works with the same operator key, so it protects only while BlockAuth runs. The lasting remedy for a
 * stolen operator key is the guardian key, which can cancel requests and replace the operator.
 */
@Component
public class ChainRequestGuard {

	private static final Logger log = LoggerFactory.getLogger(ChainRequestGuard.class);

	private final WalletRegistryClient registry;
	private final AdminActionRepository actions;
	private final EmergencyStopService stop;
	private final AuditService audit;
	private final com.blockauth.common.JobLock jobs;

	/** The first block not scanned yet. 0 at startup, so the whole chain is checked once. */
	private long nextBlock;
	/** Wallets seen with a reactivation request; checked on every run until it is gone. */
	private final Set<String> watched = new LinkedHashSet<>();

	public ChainRequestGuard(WalletRegistryClient registry, AdminActionRepository actions, EmergencyStopService stop,
			AuditService audit, com.blockauth.common.JobLock jobs) {
		this.jobs = jobs;
		this.registry = registry;
		this.actions = actions;
		this.stop = stop;
		this.audit = audit;
	}

	// ponytail: scans the event log from block 0 after every restart; fine for a local chain, but on a
	// public network start from the contract's deployment block instead.
	@Scheduled(cron = "${blockauth.chain-sync.schedule:*/15 * * * * *}")
	public void scheduledRun() {
		jobs.runExclusive("chain-request-guard", java.time.Duration.ofMinutes(5), this::check);
	}

	public synchronized void check() {
		try {
			long latest = registry.blockNumber();
			if (latest + 1 < nextBlock) {
				// The chain was reset: everything it holds is new.
				nextBlock = 0;
				watched.clear();
			}
			if (nextBlock <= latest) {
				watched.addAll(registry.walletsWithActivationRequests(nextBlock, latest));
				nextBlock = latest + 1;
			}
			watched.removeIf(this::settledActivation);
			if (registry.unpauseReadyAt() != null && !actions.isOpen(AdminAction.Type.LIFT_EMERGENCY_STOP, null)) {
				registry.cancelUnpause();
				audit.recordSecurityEvent(AttackType.UNEXPECTED_CHAIN_REQUEST, "Unpause cancelled.", null, null, null);
			}
			if (stop.isActive() && !actions.isOpen(AdminAction.Type.LIFT_EMERGENCY_STOP, null) && !registry.paused()) {
				stop.pauseOnChain();
			}
		}
		catch (BlockAuthException e) {
			log.warn("Chain request check skipped: {}", e.code());
		}
	}

	/** @return true once the wallet's request needs no more watching: carried out, cancelled, or cancelled now */
	private boolean settledActivation(String wallet) {
		if (registry.activationReadyAt(wallet) == null) {
			return true;
		}
		if (actions.isOpen(AdminAction.Type.ACTIVATE_WALLET, wallet)) {
			return false;
		}
		registry.cancelActivation(wallet);
		audit.recordSecurityEvent(AttackType.UNEXPECTED_CHAIN_REQUEST,
				"Reactivation of " + WalletAddresses.checksum(wallet) + " cancelled.", wallet, null, null);
		return true;
	}

}
