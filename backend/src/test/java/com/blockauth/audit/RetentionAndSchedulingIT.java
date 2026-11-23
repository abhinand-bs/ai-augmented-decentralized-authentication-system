package com.blockauth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.blockauth.IntegrationTest;

/** Audit findings A-03 (scheduler threads) and A-10 (optional retention, off by default). */
class RetentionAndSchedulingIT extends IntegrationTest {

	@Autowired
	private RetentionJob retention;

	@Autowired
	private ThreadPoolTaskScheduler scheduler;

	@Test
	void backgroundJobsHaveMoreThanOneThread() {
		assertThat(scheduler.getPoolSize()).isGreaterThanOrEqualTo(4);
	}

	@Test
	void retentionIsOffByDefaultAndDeletesOnlyOlderRows() {
		String wallet = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";
		java.sql.Timestamp old = java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(40)));
		java.sql.Timestamp recent = java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(5)));
		for (java.sql.Timestamp at : new java.sql.Timestamp[] { old, recent }) {
			jdbc.update("insert into login_assessments (wallet_address, score, rules, decision, assessed_at) "
					+ "values (?, 100, '', 'ALLOW', ?)", wallet, at);
			jdbc.update("insert into authentication_events (wallet_address, event_type, event_status, occurred_at) "
					+ "values (?, 'LOGIN', 'SUCCESS', ?)", wallet, at);
			jdbc.update("insert into security_events (wallet_address, attack_type, description, result, occurred_at) "
					+ "values (?, 'REPLAY_ATTEMPT', 'x', 'BLOCKED', ?)", wallet, at);
		}

		retention.run(); // RETENTION_DAYS is 0 by default: nothing is deleted
		assertThat(count("select count(*) from login_assessments")).isEqualTo(2);

		assertThat(retention.purgeOlderThan(30)).isEqualTo(3);
		assertThat(count("select count(*) from login_assessments")).isEqualTo(1);
		assertThat(count("select count(*) from authentication_events")).isEqualTo(1);
		assertThat(count("select count(*) from security_events")).isEqualTo(1);
	}

}
