package com.blockauth.risk.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class AnomalyModelTest {

	private final AnomalyModel model = AnomalyTestSupport.coldStartModel(3);

	private static RiskFeatures.Facts ordinary() {
		Instant now = Instant.parse("2026-11-18T14:10:00Z"); // a Wednesday afternoon
		return new RiskFeatures.Facts(now, true, true, true, 0, now.minus(Duration.ofDays(1)), 1, 1, 1, false,
				now.minus(Duration.ofDays(200)), false, null, 3, false);
	}

	@Test
	void featuresAreDerivedFromTheFactsAndCarryNoIdentifiers() {
		// 2026-11-23 is a Monday: day_of_week 0.
		RiskFeatures.Facts facts = new RiskFeatures.Facts(Instant.parse("2026-11-23T18:00:00Z"), true, false, false, 3,
				null, 4, 5, 2, true, Instant.parse("2026-11-13T18:00:00Z"), true, 1, 3, true);

		Map<String, Double> named = RiskFeatures.named(RiskFeatures.of(facts));

		assertThat(named.keySet()).containsExactlyElementsOf(RiskFeatures.NAMES);
		assertThat(named).containsEntry("hour_sin", -1.0).containsEntry("hour_cos", 0.0)
			.containsEntry("day_of_week", 0.0).containsEntry("new_device", 1.0).containsEntry("new_network", 1.0)
			.containsEntry("recent_failures", 1.3863).containsEntry("logins_24h", 4.0)
			.containsEntry("distinct_ips_7d", 5.0).containsEntry("distinct_devices_30d", 2.0)
			.containsEntry("has_second_step", 1.0).containsEntry("wallet_age_days", 2.3979)
			.containsEntry("flagged", 1.0).containsEntry("flagged_proximity", 1.0)
			.containsEntry("user_agent_changed", 1.0);
		assertThat(named.get("minutes_since_last_login")).isEqualTo(13.8155);
		assertThat(RiskFeatures.fromNamed(named)).hasSize(RiskFeatures.NAMES.size());
		assertThat(RiskFeatures.fromNamed(Map.of("hour_sin", 1))).isNull();
	}

	@Test
	void aFirstSignInHasNoNewDeviceOrNetworkSignal() {
		RiskFeatures.Facts first = new RiskFeatures.Facts(Instant.parse("2026-11-23T12:00:00Z"), false, false, false, 0,
				null, 0, 0, 0, false, Instant.parse("2026-11-23T11:59:00Z"), false, null, 3, false);

		double[] x = RiskFeatures.of(first);

		assertThat(x[RiskFeatures.NAMES.indexOf("new_device")]).isZero();
		assertThat(x[RiskFeatures.NAMES.indexOf("new_network")]).isZero();
	}

	@Test
	void theExplanationNamesWhatMadeTheSignInUnusual() {
		RiskFeatures.Facts o = ordinary();
		// New device, new network and another browser, at three in the morning.
		RiskFeatures.Facts night = new RiskFeatures.Facts(Instant.parse("2026-11-18T03:05:00Z"), true, false, false, 0,
				o.lastLoginAt(), 1, 1, 1, false, o.walletCreatedAt(), false, null, 3, true);

		AnomalyModel.Outcome usual = model.assess(RiskFeatures.of(o));
		AnomalyModel.Outcome unusual = model.assess(RiskFeatures.of(night));

		assertThat(usual.score()).isLessThan(0.6);
		assertThat(usual.topFeatures()).hasSizeLessThanOrEqualTo(2);
		assertThat(unusual.score()).isGreaterThan(usual.score()).isGreaterThan(0.6);
		assertThat(unusual.topFeatures()).hasSize(2)
			.isSubsetOf("hour_sin", "hour_cos", "new_device", "new_network", "user_agent_changed");
		assertThat(unusual.version()).isEqualTo(3);
	}

	@Test
	void mediansAreTheMiddleValues() {
		assertThat(AnomalyModel.medians(new double[][] { { 1, 9 }, { 3, 7 }, { 2, 8 } })).containsExactly(2, 8);
		assertThat(AnomalyModel.medians(new double[][] { { 1 }, { 3 } })).containsExactly(2);
	}

	/** The budget is 20 ms per sign-in for scoring plus the explanation; this measures it on this machine. */
	@Test
	void scoringWithTheExplanationStaysWithinTheBudget() {
		SplittableRandom random = new SplittableRandom(3);
		double[][] inputs = new double[2000][];
		Arrays.setAll(inputs, i -> RiskFeatures.of(SyntheticSignIns.benignFacts(random)));
		for (int i = 0; i < 500; i++) {
			model.assess(inputs[i]); // warm-up
		}
		long[] nanos = new long[inputs.length];
		for (int i = 0; i < inputs.length; i++) {
			long start = System.nanoTime();
			model.assess(inputs[i]);
			nanos[i] = System.nanoTime() - start;
		}
		Arrays.sort(nanos);
		double p95 = nanos[(int) (nanos.length * 0.95)] / 1e6;
		double max = nanos[nanos.length - 1] / 1e6;
		System.out.printf("Anomaly scoring with explanation: p95 %.3f ms, max %.3f ms (n=%d)%n", p95, max, nanos.length);
		assertThat(p95).isLessThan(20);
	}

}
