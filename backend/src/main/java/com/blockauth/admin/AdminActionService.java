package com.blockauth.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.blockauth.audit.AuditService;
import com.blockauth.audit.AuthEventStatus;
import com.blockauth.audit.AuthEventType;
import com.blockauth.audit.ClientInfo;
import com.blockauth.auth.StepUpAction;
import com.blockauth.auth.StepUpDetails;
import com.blockauth.auth.StepUpProof;
import com.blockauth.auth.StepUpService;
import com.blockauth.common.BlockAuthException;
import com.blockauth.common.ErrorCode;
import com.blockauth.common.PageResponse;
import com.blockauth.common.Specs;
import com.blockauth.common.WalletAddresses;
import com.blockauth.config.SecuritySettings;
import com.blockauth.secondstep.SecondStepService;
import com.blockauth.security.BlockAuthPrincipal;
import com.blockauth.user.User;
import com.blockauth.user.UserRepository;
import com.blockauth.user.UserRole;
import com.blockauth.user.UserStatus;

/**
 * Administrator actions that grant or restore access take effect only after a waiting period, during
 * which any administrator can cancel them: reactivating a wallet, resetting a user's second step, and
 * lifting the emergency stop. The request needs a fresh signature; the server then carries the action
 * out from its stored record, using its own clock. A stolen administrator session or wallet therefore
 * cannot grant access unnoticed in one step.
 */
@Service
public class AdminActionService {

	public record ActionView(Long id, AdminAction.Type action, String targetWallet, String requestedBy,
			Instant requestedAt, Instant executeAfter, AdminAction.Status status, Instant decidedAt,
			String failureReason) {
	}

	private static final Logger log = LoggerFactory.getLogger(AdminActionService.class);
	private static final Duration RETRY_AFTER = Duration.ofMinutes(1);
	private static final Set<ErrorCode> TEMPORARY = Set.of(ErrorCode.BLOCKCHAIN_UNAVAILABLE,
			ErrorCode.BLOCKCHAIN_TIMEOUT, ErrorCode.DATABASE_UNAVAILABLE, ErrorCode.CHAIN_DELAY_PENDING);

	private final AdminActionRepository actions;
	private final AdminWalletService wallets;
	private final SecondStepService secondStep;
	private final EmergencyStopService stop;
	private final UserRepository users;
	private final StepUpService stepUp;
	private final AuditService audit;
	private final SecuritySettings settings;
	private final Clock clock;

	public AdminActionService(AdminActionRepository actions, AdminWalletService wallets, SecondStepService secondStep,
			EmergencyStopService stop, UserRepository users, StepUpService stepUp, AuditService audit,
			SecuritySettings settings, Clock clock) {
		this.actions = actions;
		this.wallets = wallets;
		this.secondStep = secondStep;
		this.stop = stop;
		this.users = users;
		this.stepUp = stepUp;
		this.audit = audit;
		this.settings = settings;
		this.clock = clock;
	}

	public ActionView requestActivation(BlockAuthPrincipal admin, String address, StepUpProof proof, ClientInfo client) {
		stop.requireInactive();
		String wallet = wallets.checkCanActivate(admin, address);
		stepUp.verify(admin, StepUpAction.ACTIVATE_WALLET, StepUpDetails.wallet(wallet), proof, client);
		return scheduleWithChain(AdminAction.Type.ACTIVATE_WALLET, wallet, admin, client,
				() -> wallets.requestActivationOnChain(wallet));
	}

	public ActionView requestSecondStepReset(BlockAuthPrincipal admin, String address, StepUpProof proof,
			ClientInfo client) {
		stop.requireInactive();
		String wallet = WalletAddresses.normalize(address, "address");
		if (wallet.equals(admin.walletAddress())) {
			throw new BlockAuthException(ErrorCode.CANNOT_MODIFY_OWN_WALLET);
		}
		User target = users.findByWalletAddress(wallet)
			.orElseThrow(() -> new BlockAuthException(ErrorCode.RESOURCE_NOT_FOUND));
		if (!secondStep.isEnabled(target.getId())) {
			throw new BlockAuthException(ErrorCode.SECOND_STEP_NOT_ENABLED);
		}
		stepUp.verify(admin, StepUpAction.RESET_SECOND_STEP, StepUpDetails.wallet(wallet), proof, client);
		return schedule(AdminAction.Type.RESET_SECOND_STEP, wallet, admin, client);
	}

	public ActionView requestLift(BlockAuthPrincipal admin, StepUpProof proof, ClientInfo client) {
		if (!stop.isActive()) {
			throw new BlockAuthException(ErrorCode.EMERGENCY_STOP_NOT_ACTIVE);
		}
		stepUp.verify(admin, StepUpAction.LIFT_EMERGENCY_STOP, StepUpDetails.none(), proof, client);
		return scheduleWithChain(AdminAction.Type.LIFT_EMERGENCY_STOP, null, admin, client, stop::requestUnpauseOnChain);
	}

	/**
	 * Any administrator may cancel a waiting action. Cancelling grants nothing, but it can block a needed
	 * reactivation or the lifting of the emergency stop, so it needs a fresh signature naming the action:
	 * a stolen session alone cannot do it.
	 */
	public ActionView cancel(BlockAuthPrincipal admin, Long id, StepUpProof proof, ClientInfo client) {
		AdminAction action = actions.findById(id).orElseThrow(() -> new BlockAuthException(ErrorCode.RESOURCE_NOT_FOUND));
		// Checked before the signature, so that a request for a finished action does not use up a confirmation.
		if (action.getStatus() != AdminAction.Status.PENDING) {
			throw new BlockAuthException(ErrorCode.ADMIN_ACTION_NOT_PENDING);
		}
		stepUp.verify(admin, StepUpAction.CANCEL_ADMIN_ACTION, details(action), proof, client);
		if (actions.cancel(id, admin.userId(), clock.instant()) == 0) {
			throw new BlockAuthException(ErrorCode.ADMIN_ACTION_NOT_PENDING);
		}
		cancelOnChain(action);
		audit.recordAuthEvent(AuthEventType.ACTION_CANCELLED, AuthEventStatus.SUCCESS, action.getTargetWallet(), null,
				admin.userId(), null, client);
		AdminAction cancelled = actions.findById(id).orElseThrow();
		return view(cancelled, users.findById(cancelled.getRequestedBy())
			.map(u -> Map.of(u.getId(), u.getWalletAddress()))
			.orElse(Map.of()));
	}

	/** The text the confirming signature covers. @throws BlockAuthException RESOURCE_NOT_FOUND */
	public String cancelDetails(Long id) {
		return details(actions.findById(id).orElseThrow(() -> new BlockAuthException(ErrorCode.RESOURCE_NOT_FOUND)));
	}

	private static String details(AdminAction action) {
		return "number " + action.getId() + " (" + action.getAction()
				+ (action.getTargetWallet() == null ? "" : " for " + WalletAddresses.checksum(action.getTargetWallet()))
				+ ")";
	}

	public PageResponse<ActionView> list(AdminAction.Status status, @NonNull Pageable pageable) {
		Page<AdminAction> page = actions.findAll(Specs.<AdminAction>eq("status", status), pageable);
		Map<UUID, String> requesters = users.findAllById(page.getContent().stream().map(AdminAction::getRequestedBy)
			.collect(Collectors.toSet())).stream().collect(Collectors.toMap(User::getId, User::getWalletAddress));
		return PageResponse.of(page, a -> view(a, requesters));
	}

	/**
	 * Carries out every action whose waiting period is over: every 15 seconds by default. The
	 * integration tests switch the schedule off ("-") and call this themselves.
	 */
	@Scheduled(cron = "${blockauth.admin-actions.schedule:*/15 * * * * *}")
	public void runDue() {
		for (AdminAction action : actions.findDue(clock.instant())) {
			try {
				execute(action);
			}
			catch (RuntimeException e) {
				log.error("Delayed action {} could not be processed: {}", action.getId(), e.getClass().getSimpleName());
			}
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void requeueInterrupted() {
		int requeued = actions.requeueInterrupted();
		if (requeued > 0) {
			log.warn("{} delayed actions were interrupted by a restart and will be carried out again", requeued);
		}
	}

	private void execute(AdminAction action) {
		// While the stop is on, only lifting it goes ahead; the rest wait (and can still be cancelled).
		if (action.getAction() != AdminAction.Type.LIFT_EMERGENCY_STOP && stop.isActive()) {
			return;
		}
		if (actions.claim(action.getId()) == 0) {
			return;
		}
		try {
			User requester = users.findById(action.getRequestedBy())
				.filter(u -> u.getRole() == UserRole.ADMIN && u.getStatus() == UserStatus.ACTIVE)
				.orElseThrow(() -> new BlockAuthException(ErrorCode.ACCESS_DENIED));
			switch (action.getAction()) {
				case ACTIVATE_WALLET ->
					wallets.executeActivation(action.getTargetWallet(), requester.getId(), requester.getWalletAddress());
				case RESET_SECOND_STEP -> resetSecondStep(action.getTargetWallet(), requester);
				case LIFT_EMERGENCY_STOP -> stop.lift(requester.getId(), requester.getWalletAddress());
			}
			actions.finish(action.getId(), AdminAction.Status.EXECUTED, null, clock.instant());
			log.info("Delayed action {} ({}) carried out", action.getId(), action.getAction());
		}
		catch (BlockAuthException e) {
			if (TEMPORARY.contains(e.code())) {
				actions.retryAt(action.getId(), clock.instant().plus(RETRY_AFTER), e.code().name());
			}
			else {
				actions.finish(action.getId(), AdminAction.Status.FAILED, e.code().name(), clock.instant());
			}
		}
		catch (DataAccessException e) {
			actions.retryAt(action.getId(), clock.instant().plus(RETRY_AFTER), ErrorCode.DATABASE_UNAVAILABLE.name());
		}
		catch (RuntimeException e) {
			// Unexpected: back to the queue rather than stuck as EXECUTING, so it stays visible and can be
			// cancelled. Carrying it out again is safe because every action re-reads the current state.
			log.error("Delayed action {} failed unexpectedly: {}", action.getId(), e.getClass().getSimpleName());
			actions.retryAt(action.getId(), clock.instant().plus(RETRY_AFTER), ErrorCode.INTERNAL_ERROR.name());
		}
	}

	private void resetSecondStep(String wallet, User requester) {
		User target = users.findByWalletAddress(wallet)
			.orElseThrow(() -> new BlockAuthException(ErrorCode.RESOURCE_NOT_FOUND));
		secondStep.remove(target.getId());
		audit.recordAuthEvent(AuthEventType.SECOND_STEP_RESET, AuthEventStatus.SUCCESS, wallet, target.getId(),
				requester.getId(), null, new ClientInfo(null, null));
	}

	/**
	 * Withdraws the chain's side of a cancelled action. If the chain cannot be reached now, the request left
	 * there no longer matches a waiting action, and the chain status check cancels it on its next run.
	 */
	private void cancelOnChain(AdminAction action) {
		try {
			switch (action.getAction()) {
				case ACTIVATE_WALLET -> wallets.cancelActivationOnChain(action.getTargetWallet());
				case LIFT_EMERGENCY_STOP -> stop.cancelUnpauseOnChain();
				case RESET_SECOND_STEP -> {
					// Off-chain only.
				}
			}
		}
		catch (BlockAuthException e) {
			log.warn("Cancelled action {} could not be withdrawn on the chain yet: {}", action.getId(), e.code());
		}
	}

	/**
	 * Records the request, then starts its waiting period on the chain. If the chain refuses, the request is
	 * marked failed rather than left waiting for something that can never be carried out.
	 */
	private ActionView scheduleWithChain(AdminAction.Type type, String wallet, BlockAuthPrincipal admin,
			ClientInfo client, Runnable onChain) {
		ActionView view = schedule(type, wallet, admin, client);
		try {
			onChain.run();
		}
		catch (BlockAuthException e) {
			actions.claim(view.id());
			actions.finish(view.id(), AdminAction.Status.FAILED, e.code().name(), clock.instant());
			throw e;
		}
		return view;
	}

	private ActionView schedule(AdminAction.Type type, String wallet, BlockAuthPrincipal admin, ClientInfo client) {
		Instant now = clock.instant();
		AdminAction saved;
		try {
			saved = actions.saveAndFlush(new AdminAction(type, wallet, admin.userId(), now,
					now.plus(settings.adminActionDelay())));
		}
		catch (DataIntegrityViolationException e) {
			throw new BlockAuthException(ErrorCode.ADMIN_ACTION_ALREADY_PENDING);
		}
		audit.recordAuthEvent(AuthEventType.ACTION_REQUESTED, AuthEventStatus.SUCCESS, wallet, null, admin.userId(), null,
				client);
		return view(saved, Map.of(admin.userId(), admin.walletAddress()));
	}

	private static ActionView view(AdminAction a, Map<UUID, String> requesters) {
		return new ActionView(a.getId(), a.getAction(), WalletAddresses.checksum(a.getTargetWallet()),
				WalletAddresses.checksum(requesters.get(a.getRequestedBy())), a.getRequestedAt(), a.getExecuteAfter(),
				a.getStatus(), a.getDecidedAt(), a.getFailureReason());
	}

}
