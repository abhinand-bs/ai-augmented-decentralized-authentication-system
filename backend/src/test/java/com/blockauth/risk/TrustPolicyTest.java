package com.blockauth.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.blockauth.config.SecuritySettings;
import com.blockauth.risk.TrustPolicy.Decision;
import com.blockauth.risk.TrustPolicy.Rule;
import com.blockauth.risk.TrustPolicy.Signals;

class TrustPolicyTest {

	private static final SecuritySettings.Trust DEFAULTS = new SecuritySettings.Trust(90, 50, 30, 20, 35, 25, 3, 35, 3, false);

	private static Signals known() {
		return new Signals(true, true, true, false, 0, false);
	}

	@Test
	void aKnownDeviceOnAKnownNetworkScoresFull() {
		TrustPolicy.Score score = TrustPolicy.score(known(), DEFAULTS);

		assertThat(score.value()).isEqualTo(100);
		assertThat(score.rules()).isEmpty();
	}

	@Test
	void eachRuleSubtractsItsPenalty() {
		assertThat(TrustPolicy.score(new Signals(true, false, true, false, 0, false), DEFAULTS).value()).isEqualTo(70);
		assertThat(TrustPolicy.score(new Signals(true, true, false, false, 0, false), DEFAULTS).value()).isEqualTo(80);
		assertThat(TrustPolicy.score(new Signals(true, true, true, true, 0, false), DEFAULTS).value()).isEqualTo(65);
		assertThat(TrustPolicy.score(new Signals(true, true, true, false, 3, false), DEFAULTS).value()).isEqualTo(75);
		assertThat(TrustPolicy.score(new Signals(true, true, true, false, 2, false), DEFAULTS).value()).isEqualTo(100);
		assertThat(TrustPolicy.score(new Signals(true, true, true, false, 0, true), DEFAULTS).value()).isEqualTo(65);
	}

	@Test
	void theScoreNeverGoesBelowZeroAndNamesEveryRule() {
		TrustPolicy.Score score = TrustPolicy.score(new Signals(true, false, false, true, 9, true), DEFAULTS);

		assertThat(score.value()).isZero();
		assertThat(score.rules()).containsExactly(Rule.NEW_DEVICE, Rule.NEW_NETWORK, Rule.FLAGGED_WALLET,
				Rule.REPEATED_FAILURES, Rule.LINKED_TO_FLAGGED);
	}

	@Test
	void aFirstSignInHasNoBaselineForDeviceOrNetwork() {
		TrustPolicy.Score score = TrustPolicy.score(new Signals(false, false, false, false, 0, false), DEFAULTS);

		assertThat(score.value()).isEqualTo(100);
		assertThat(score.rules()).isEmpty();
	}

	@Test
	void decisionsFollowTheThresholds() {
		assertThat(TrustPolicy.decide(90, true, DEFAULTS)).isEqualTo(Decision.ALLOW);
		assertThat(TrustPolicy.decide(90, false, DEFAULTS)).isEqualTo(Decision.ALLOW);
		// Medium: the second step when it exists, otherwise the signature alone.
		assertThat(TrustPolicy.decide(70, true, DEFAULTS)).isEqualTo(Decision.SECOND_STEP);
		assertThat(TrustPolicy.decide(50, false, DEFAULTS)).isEqualTo(Decision.ALLOW);
		// Low: the second step is required; without one the sign-in is refused.
		assertThat(TrustPolicy.decide(49, true, DEFAULTS)).isEqualTo(Decision.SECOND_STEP);
		assertThat(TrustPolicy.decide(49, false, DEFAULTS)).isEqualTo(Decision.REFUSE);
	}

	@Test
	void theAnomalyPenaltyFollowsTheFormula() {
		assertThat(TrustPolicy.anomalyPenalty(0.5, 0.6, 30)).isZero();
		assertThat(TrustPolicy.anomalyPenalty(0.6, 0.6, 30)).isZero();
		assertThat(TrustPolicy.anomalyPenalty(0.8, 0.6, 30)).isEqualTo(15);
		assertThat(TrustPolicy.anomalyPenalty(1.0, 0.6, 30)).isEqualTo(30);
		assertThat(TrustPolicy.anomalyPenalty(1.5, 0.6, 30)).isEqualTo(30);
		assertThat(TrustPolicy.anomalyPenalty(Double.NaN, 0.6, 30)).isZero();
	}

	@Test
	void theAnomalyOnlyLowersAScoreAndAddsItsReason() {
		TrustPolicy.Score rules = TrustPolicy.score(new Signals(true, false, true, false, 0, false), DEFAULTS);

		TrustPolicy.Score unusual = TrustPolicy.withAnomaly(rules, 0.9, 0.6, 30);
		TrustPolicy.Score usual = TrustPolicy.withAnomaly(rules, 0.3, 0.6, 30);

		assertThat(unusual.value()).isEqualTo(70 - 23);
		assertThat(unusual.rules()).containsExactly(Rule.NEW_DEVICE, Rule.ANOMALY_DETECTED);
		assertThat(usual).isEqualTo(rules);
		assertThat(TrustPolicy.withAnomaly(new TrustPolicy.Score(10, java.util.List.of()), 1.0, 0.6, 30).value())
			.isZero();
	}

	@Test
	void theDecisionThresholdsAreUnchangedByTheModel() {
		// 70 - 23 = 47: below second-step-at, so without a second step the sign-in is refused, with one it is asked.
		TrustPolicy.Score unusual = TrustPolicy.withAnomaly(
				TrustPolicy.score(new Signals(true, false, true, false, 0, false), DEFAULTS), 0.9, 0.6, 30);
		assertThat(TrustPolicy.decide(unusual.value(), false, DEFAULTS)).isEqualTo(Decision.REFUSE);
		assertThat(TrustPolicy.decide(unusual.value(), true, DEFAULTS)).isEqualTo(Decision.SECOND_STEP);
		assertThat(TrustPolicy.decide(70, false, DEFAULTS)).isEqualTo(Decision.ALLOW);
	}

}
