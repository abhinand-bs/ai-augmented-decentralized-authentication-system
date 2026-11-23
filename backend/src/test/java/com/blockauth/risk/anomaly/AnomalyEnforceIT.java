package com.blockauth.risk.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/**
 * ENFORCE mode: the anomaly lowers the rule score by round(maxPenalty * clamp((a - t) / (1 - t))), never raises
 * it, and adds ANOMALY_DETECTED. The threshold is set to 0.5 here so that the effect is visible on ordinary
 * test sign-ins; the formula and the decision thresholds are the production ones.
 */
@TestPropertySource(properties = { "blockauth.security.anomaly.mode=enforce", "blockauth.security.anomaly.threshold=0.5" })
class AnomalyEnforceIT extends IntegrationTest {

	@Test
	void theAnomalyCanOnlyLowerTheScoreAndTheDecisionFollowsTheLoweredScore() {
		clock.set(Instant.parse("2026-11-22T03:10:00Z")); // a Sunday, three in the morning
		TestWallet wallet = new TestWallet();
		registeredAndLoggedIn(wallet);
		login(browser(), wallet); // a new device at an odd hour

		Map<String, Object> row = jdbc.queryForMap("select rule_score, final_score, score, anomaly_score, rules, mode, "
				+ "decision from login_assessments where wallet_address = ? order by id desc limit 1", wallet.address());

		double a = (Double) row.get("anomaly_score");
		int expectedPenalty = (int) Math.round(30 * Math.max(0, Math.min(1, (a - 0.5) / 0.5)));
		assertThat(row.get("mode")).isEqualTo("ENFORCE");
		assertThat(row.get("rule_score")).isEqualTo(70);
		assertThat(row.get("final_score")).isEqualTo(70 - expectedPenalty);
		assertThat(row.get("score")).isEqualTo(row.get("final_score"));
		assertThat((int) row.get("final_score")).isLessThanOrEqualTo((int) row.get("rule_score"));
		assertThat(expectedPenalty).as("an odd-hour new device must look unusual to the model").isPositive();
		assertThat((String) row.get("rules")).isEqualTo("NEW_DEVICE,ANOMALY_DETECTED");
		// 70 - penalty is still >= 50 here only if the penalty is at most 20; the decision follows the final score.
		assertThat(row.get("decision")).isEqualTo(70 - expectedPenalty >= 50 ? "ALLOW" : "REFUSE");
	}

}
