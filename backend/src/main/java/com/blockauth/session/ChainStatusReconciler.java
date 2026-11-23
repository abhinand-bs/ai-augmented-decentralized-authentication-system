package com.blockauth.session;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.blockauth.audit.AuditService;
import com.blockauth.audit.AuthEventStatus;
import com.blockauth.audit.AuthEventType;
import com.blockauth.audit.ClientInfo;
import com.blockauth.blockchain.OnChainWalletStatus;
import com.blockauth.common.BlockAuthException;
import com.blockauth.user.User;
import com.blockauth.user.UserService;

/**
 * Notices wallet status changes the application did not make itself: a revocation sent to the contract
 * directly with the operator key, or one whose transaction was mined only after the client stopped waiting
 * for it. Session checks read the database mirror, never the chain, so without this such a session would
 * stay open until something happened to read that wallet's status again.
 * <p>
 * Every 15 seconds by default it reads the chain for each wallet that has an open session, corrects the
 * mirror, and ends the sessions of a wallet that is no longer registered and active. When the chain cannot
 * be read it changes nothing and tries again on the next run: sessions do not depend on the chain.
 */
@Component
public class ChainStatusReconciler {

	private static final Logger log = LoggerFactory.getLogger(ChainStatusReconciler.class);

	private final UserSessionRepository sessions;
	private final UserService users;
	private final AuditService audit;
	private final Clock clock;

	public ChainStatusReconciler(UserSessionRepository sessions, UserService users, AuditService audit, Clock clock) {
		this.sessions = sessions;
		this.users = users;
		this.audit = audit;
		this.clock = clock;
	}

	// ponytail: one chain read per signed-in wallet and run; poll the contract's events instead if the
	// number of open sessions grows large.
	@Scheduled(cron = "${blockauth.chain-sync.schedule:*/15 * * * * *}")
	public void reconcile() {
		for (User user : sessions.findUsersWithOpenSessions()) {
			OnChainWalletStatus chain;
			try {
				// Also corrects the mirror (users.status) when the chain disagrees with it.
				chain = users.walletState(user.getWalletAddress()).chain();
			}
			catch (BlockAuthException e) {
				log.warn("Chain status check skipped: {}", e.code());
				return;
			}
			if (!chain.valid()) {
				int ended = sessions.endAllForUser(user.getId(), SessionEndReason.WALLET_REVOKED, clock.instant());
				if (ended > 0) {
					audit.recordAuthEvent(AuthEventType.SESSION_TERMINATED, AuthEventStatus.SUCCESS,
							user.getWalletAddress(), user.getId(), null, null, new ClientInfo(null, null));
					log.info("Ended {} sessions of wallet {}: the chain shows it is {}", ended, user.getWalletAddress(),
							chain.label());
				}
			}
		}
	}

}
