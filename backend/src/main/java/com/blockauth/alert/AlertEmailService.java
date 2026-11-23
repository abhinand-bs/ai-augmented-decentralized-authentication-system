package com.blockauth.alert;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

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
import com.blockauth.common.Tokens;
import com.blockauth.config.SecuritySettings;
import com.blockauth.secondstep.SecretBox;
import com.blockauth.security.BlockAuthPrincipal;
import com.blockauth.security.RateLimiter;
import com.blockauth.session.NewDeviceSignIn;

/**
 * The opt-in alert address of a wallet. Setting or removing it needs a fresh wallet signature; a new address
 * receives a 6-digit code (stored as a keyed hash, valid 15 minutes, 5 attempts) and gets alerts only once the
 * code is entered. Changing the address tells the old one. Off unless MAIL_ENABLED=true.
 */
@Service
public class AlertEmailService {

	public record Status(boolean available, String address, boolean verified, Instant codeExpiresAt) {
	}

	static final Duration CODE_LIFETIME = Duration.ofMinutes(15);
	static final int MAX_ATTEMPTS = 5;

	private final NamedParameterJdbcTemplate jdbc;
	private final StepUpService stepUp;
	private final AuditService audit;
	private final AlertMailer mailer;
	private final RateLimiter limiter;
	private final Clock clock;
	private final SecretBox box;
	private final byte[] hashKey;
	private final boolean enabled;
	private final int maxAlertsPerHour;
	private final SecureRandom random = new SecureRandom();

	public AlertEmailService(NamedParameterJdbcTemplate jdbc, StepUpService stepUp, AuditService audit,
			AlertMailer mailer, RateLimiter limiter, Clock clock, SecuritySettings settings,
			@Value("${blockauth.mail.enabled:false}") boolean enabled,
			@Value("${blockauth.mail.max-alerts-per-hour:3}") int maxAlertsPerHour) {
		this.jdbc = jdbc;
		this.stepUp = stepUp;
		this.audit = audit;
		this.mailer = mailer;
		this.limiter = limiter;
		this.clock = clock;
		this.box = new SecretBox(settings.secondStepKeyBytes());
		this.hashKey = settings.secondStepKeyBytes();
		this.enabled = enabled;
		this.maxAlertsPerHour = maxAlertsPerHour;
	}

	public Status status(BlockAuthPrincipal principal) {
		if (!enabled) {
			return new Status(false, null, false, null);
		}
		List<Map<String, Object>> rows = jdbc.queryForList("select address_ciphertext, verified_at, code_expires_at "
				+ "from alert_emails where user_id = :id", Map.of("id", principal.userId()));
		if (rows.isEmpty()) {
			return new Status(true, null, false, null);
		}
		Map<String, Object> row = rows.get(0);
		Timestamp expires = (Timestamp) row.get("code_expires_at");
		return new Status(true, AlertEmails.mask(open((String) row.get("address_ciphertext"))),
				row.get("verified_at") != null, expires == null ? null : expires.toInstant());
	}

	/** Stores the address unverified and mails it a code. A previous verified address is told. */
	public Status set(BlockAuthPrincipal principal, String email, StepUpProof proof, ClientInfo client) {
		requireEnabled();
		String address = AlertEmails.normalize(email);
		RateLimiter.Decision decision = limiter.acquire("alert-email-set", principal.userId().toString(), 5,
				Duration.ofHours(1));
		if (!decision.allowed()) {
			throw new BlockAuthException(ErrorCode.RATE_LIMITED, decision.retryAfterSeconds());
		}
		stepUp.verify(principal, StepUpAction.SET_ALERT_EMAIL, StepUpDetails.email(address), proof, client);
		String previous = verifiedAddress(principal.userId());
		String code = String.format("%06d", random.nextInt(1_000_000));
		Instant now = clock.instant();
		jdbc.update("""
				insert into alert_emails (user_id, address_ciphertext, verified_at, code_hash, code_expires_at, attempts,
				                          created_at)
				values (:id, :address, null, :hash, :expires, 0, :now)
				on conflict (user_id) do update set address_ciphertext = excluded.address_ciphertext, verified_at = null,
				  code_hash = excluded.code_hash, code_expires_at = excluded.code_expires_at, attempts = 0
				""", new MapSqlParameterSource().addValue("id", principal.userId())
			.addValue("address", box.seal(address.getBytes(StandardCharsets.UTF_8)))
			.addValue("hash", codeHash(principal.userId(), code))
			.addValue("expires", Timestamp.from(now.plus(CODE_LIFETIME)))
			.addValue("now", Timestamp.from(now)));
		audit.recordAuthEvent(AuthEventType.ALERT_EMAIL_SET, AuthEventStatus.SUCCESS, principal.walletAddress(),
				principal.userId(), null, null, client);
		mailer.verificationCode(principal.userId(), principal.walletAddress(), address, code);
		if (previous != null && !previous.equals(address)) {
			mailer.addressChanged(principal.userId(), principal.walletAddress(), previous);
		}
		return status(principal);
	}

	public Status verify(BlockAuthPrincipal principal, String code, ClientInfo client) {
		requireEnabled();
		Map<String, Object> row = jdbc.queryForList("select code_hash, code_expires_at, attempts from alert_emails "
				+ "where user_id = :id and code_hash is not null", Map.of("id", principal.userId()))
			.stream().findFirst().orElseThrow(() -> new BlockAuthException(ErrorCode.ALERT_EMAIL_NOT_PENDING));
		Instant expires = ((Timestamp) row.get("code_expires_at")).toInstant();
		int attempts = ((Number) row.get("attempts")).intValue();
		if (!clock.instant().isBefore(expires) || attempts >= MAX_ATTEMPTS) {
			throw new BlockAuthException(ErrorCode.ALERT_EMAIL_NOT_PENDING);
		}
		// Counted first, atomically, so parallel guesses cannot exceed the attempts.
		int counted = jdbc.update("update alert_emails set attempts = attempts + 1 where user_id = :id "
				+ "and code_hash is not null and attempts < :max", Map.of("id", principal.userId(), "max", MAX_ATTEMPTS));
		boolean match = code != null && java.security.MessageDigest.isEqual(
				codeHash(principal.userId(), code.trim()).getBytes(StandardCharsets.UTF_8),
				((String) row.get("code_hash")).getBytes(StandardCharsets.UTF_8));
		if (counted == 0 || !match) {
			throw new BlockAuthException(ErrorCode.VERIFICATION_CODE_INVALID);
		}
		jdbc.update("update alert_emails set verified_at = :now, code_hash = null, code_expires_at = null, attempts = 0 "
				+ "where user_id = :id", Map.of("id", principal.userId(), "now", Timestamp.from(clock.instant())));
		audit.recordAuthEvent(AuthEventType.ALERT_EMAIL_VERIFIED, AuthEventStatus.SUCCESS, principal.walletAddress(),
				principal.userId(), null, null, client);
		return status(principal);
	}

	public void remove(BlockAuthPrincipal principal, StepUpProof proof, ClientInfo client) {
		requireEnabled();
		stepUp.verify(principal, StepUpAction.REMOVE_ALERT_EMAIL, StepUpDetails.none(), proof, client);
		if (jdbc.update("delete from alert_emails where user_id = :id", Map.of("id", principal.userId())) == 0) {
			throw new BlockAuthException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		audit.recordAuthEvent(AuthEventType.ALERT_EMAIL_REMOVED, AuthEventStatus.SUCCESS, principal.walletAddress(),
				principal.userId(), null, null, client);
	}

	/**
	 * Runs once the sign-in's own transaction has committed, so a mail problem can never fail or roll back the
	 * sign-in; the mail itself is sent in the background. At most {@code max-alerts-per-hour} per user.
	 */
	@TransactionalEventListener
	public void onNewDevice(NewDeviceSignIn event) {
		if (!enabled) {
			return;
		}
		String address = verifiedAddress(event.userId());
		if (address == null) {
			return;
		}
		if (!limiter.acquire("alert-email-sent", event.userId().toString(), maxAlertsPerHour, Duration.ofHours(1))
			.allowed()) {
			audit.recordAuthEvent(AuthEventType.ALERT_EMAIL_LIMITED, AuthEventStatus.BLOCKED, event.walletAddress(),
					event.userId(), null, null, new ClientInfo(null, null));
			return;
		}
		mailer.signIn(event.userId(), event.walletAddress(), address, event.deviceLabel(), event.at(), event.ip());
	}

	private String verifiedAddress(UUID userId) {
		return jdbc.queryForList("select address_ciphertext from alert_emails where user_id = :id "
				+ "and verified_at is not null", Map.of("id", userId), String.class).stream().findFirst()
			.map(this::open).orElse(null);
	}

	private String open(String sealed) {
		return new String(box.open(sealed), StandardCharsets.UTF_8);
	}

	private String codeHash(UUID userId, String code) {
		return Tokens.hmacSha256Hex(hashKey, userId + ":" + code);
	}

	private void requireEnabled() {
		if (!enabled) {
			throw new BlockAuthException(ErrorCode.ALERT_EMAIL_UNAVAILABLE);
		}
	}

}
