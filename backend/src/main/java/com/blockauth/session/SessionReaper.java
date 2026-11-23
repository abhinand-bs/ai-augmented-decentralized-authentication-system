package com.blockauth.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.blockauth.common.JobLock;

/**
 * Closes session records whose session has been idle longer than the session timeout (plus a grace period
 * covering the one-minute granularity of "last seen"), with reason EXPIRED. It does not depend on the session
 * store announcing expiries, which Redis does not do reliably; without Redis it is a backstop for the servlet
 * container's own expiry hook.
 */
@Component
public class SessionReaper {

	private static final Logger log = LoggerFactory.getLogger(SessionReaper.class);
	static final Duration GRACE = Duration.ofMinutes(2);

	private final UserSessionRepository sessions;
	private final Clock clock;
	private final Duration timeout;
	private final JobLock jobs;

	public SessionReaper(UserSessionRepository sessions, Clock clock,
			@Value("${server.servlet.session.timeout:30m}") Duration timeout, JobLock jobs) {
		this.sessions = sessions;
		this.clock = clock;
		this.timeout = timeout;
		this.jobs = jobs;
	}

	@Scheduled(cron = "${blockauth.session-reaper.schedule:30 * * * * *}")
	public void scheduledRun() {
		jobs.runExclusive("session-reaper", Duration.ofMinutes(5), this::closeExpired);
	}

	/** @return records closed */
	public int closeExpired() {
		Instant now = clock.instant();
		int closed = sessions.endIdleSince(now.minus(timeout).minus(GRACE), SessionEndReason.EXPIRED, now);
		if (closed > 0) {
			log.info("Closed {} expired session records", closed);
		}
		return closed;
	}

}
