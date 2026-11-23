package com.blockauth.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional retention for the three tables that grow with every sign-in: sign-in assessments, authentication
 * events and security events. Off by default ({@code RETENTION_DAYS=0}): nothing is ever deleted unless an
 * operator decides how long the audit trail must be kept. Deleting is idempotent, so two instances running it
 * at once do no harm.
 */
@Component
public class RetentionJob {

	private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final int days;

	public RetentionJob(JdbcTemplate jdbc, Clock clock, @Value("${blockauth.retention.days:0}") int days) {
		if (days < 0) {
			throw new IllegalArgumentException("RETENTION_DAYS must be 0 (keep everything) or a positive number of days");
		}
		this.jdbc = jdbc;
		this.clock = clock;
		this.days = days;
	}

	@Scheduled(cron = "${blockauth.retention.schedule:0 15 4 * * *}")
	public void run() {
		if (days > 0) {
			purgeOlderThan(days);
		}
	}

	/** @return rows deleted from the three tables together */
	public int purgeOlderThan(int keepDays) {
		Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(keepDays)));
		int deleted = jdbc.update("delete from login_assessments where assessed_at < ?", cutoff)
				+ jdbc.update("delete from authentication_events where occurred_at < ?", cutoff)
				+ jdbc.update("delete from security_events where occurred_at < ?", cutoff);
		if (deleted > 0) {
			log.info("Retention: deleted {} rows older than {} days (before {})", deleted, keepDays,
					Instant.ofEpochMilli(cutoff.getTime()));
		}
		return deleted;
	}

}
