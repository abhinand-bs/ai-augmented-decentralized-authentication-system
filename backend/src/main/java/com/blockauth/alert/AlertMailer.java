package com.blockauth.alert;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.blockauth.audit.AuditService;
import com.blockauth.audit.AuthEventStatus;
import com.blockauth.audit.AuthEventType;
import com.blockauth.audit.ClientInfo;

/**
 * Sends the three kinds of alert mail, always in the background on a small bounded executor, so a slow or
 * unreachable mail server never delays or fails a request. Plain text, constant subjects, no links; every value
 * from a client is stripped of line breaks and control characters; recipients are addresses this server checked.
 * Every outcome is audited (sent, failed); credentials and addresses never reach the log.
 */
@Component
public class AlertMailer {

	private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);
	private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
		.withZone(ZoneOffset.UTC);
	static final String SIGN_IN_SUBJECT = "BlockAuth: your wallet signed in on a new device";
	static final String VERIFY_SUBJECT = "BlockAuth: confirm your alert e-mail address";
	static final String CHANGED_SUBJECT = "BlockAuth: your alert e-mail address was changed";

	private final ObjectProvider<JavaMailSender> sender;
	private final AuditService audit;
	private final String from;
	private final TaskExecutor executor;

	public AlertMailer(ObjectProvider<JavaMailSender> sender, AuditService audit,
			@Value("${blockauth.mail.from:}") String from) {
		this.sender = sender;
		this.audit = audit;
		this.from = from;
		ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
		pool.setCorePoolSize(1);
		pool.setMaxPoolSize(2);
		pool.setQueueCapacity(100);
		pool.setThreadNamePrefix("alert-mail-");
		pool.initialize();
		this.executor = pool;
	}

	void verificationCode(UUID userId, String wallet, String to, String code) {
		send(userId, wallet, to, VERIFY_SUBJECT, """
				Enter this code on the Account security page of BlockAuth to confirm this address:

				    %s

				The code is valid for 15 minutes. If you did not ask for this, ignore this e-mail: no alerts will be
				sent to this address unless the code is entered.
				""".formatted(code));
	}

	void addressChanged(UUID userId, String wallet, String oldAddress) {
		send(userId, wallet, oldAddress, CHANGED_SUBJECT, """
				Sign-in alerts for your BlockAuth wallet will no longer be sent to this address: another address was
				set. If this was not you, open BlockAuth, sign in with your wallet and check Account security.
				""");
	}

	void signIn(UUID userId, String wallet, String to, String deviceLabel, Instant at, String ip) {
		send(userId, wallet, to, SIGN_IN_SUBJECT, """
				Your wallet signed in to BlockAuth on a device it had not used before.

				Device:   %s
				Time:     %s
				Network:  %s

				If this was not you, open BlockAuth and choose This was not me.
				""".formatted(AlertEmails.plain(deviceLabel), UTC.format(at), AlertEmails.coarseIp(ip)));
	}

	private void send(UUID userId, String wallet, String to, String subject, String body) {
		Optional<JavaMailSender> mail = Optional.ofNullable(sender.getIfAvailable());
		if (mail.isEmpty()) {
			return;
		}
		try {
			executor.execute(() -> deliver(mail.get(), userId, wallet, to, subject, body));
		}
		catch (RejectedExecutionException e) {
			log.warn("Alert mail queue full: one alert e-mail dropped");
			audit.recordAuthEvent(AuthEventType.ALERT_EMAIL_FAILED, AuthEventStatus.FAILURE, wallet, userId, null, null,
					new ClientInfo(null, null));
		}
	}

	private void deliver(JavaMailSender mail, UUID userId, String wallet, String to, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);
		try {
			mail.send(message);
			audit.recordAuthEvent(AuthEventType.ALERT_EMAIL_SENT, AuthEventStatus.SUCCESS, wallet, userId, null, null,
					new ClientInfo(null, null));
		}
		catch (RuntimeException e) {
			log.warn("Alert e-mail could not be sent: {}", e.getClass().getSimpleName());
			audit.recordAuthEvent(AuthEventType.ALERT_EMAIL_FAILED, AuthEventStatus.FAILURE, wallet, userId, null, null,
					new ClientInfo(null, null));
		}
	}

}
