package com.blockauth.risk.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Evaluation on SYNTHETIC data only: a benign population drawn with a different seed than the one the model
 * was trained on, and four made-up attack scenarios. The rates measured here say how well the model separates
 * these made-up groups; they are not a claim about real-world accuracy. The asserted bounds were set from the
 * measured values (see docs/RISK_MODEL.md) with a margin, so a change that makes the model clearly worse fails.
 */
class AnomalyEvaluationTest {

	private static final int N = 2000;
	private static final double THRESHOLD = 0.6;

	private final AnomalyModel model = AnomalyTestSupport.coldStartModel(1);

	private static RiskFeatures.Facts with(RiskFeatures.Facts f, Instant now, Boolean knownDevice,
			Boolean knownNetwork, Long failures, Long logins24h, Long ips7d, Long devices30d, Integer hops,
			Boolean uaChanged) {
		return new RiskFeatures.Facts(now == null ? f.now() : now, true,
				knownDevice == null ? f.knownDevice() : knownDevice, knownNetwork == null ? f.knownNetwork() : knownNetwork,
				failures == null ? f.recentFailures() : failures, f.lastLoginAt() == null ? f.now().minus(Duration.ofDays(1))
						: f.lastLoginAt(),
				logins24h == null ? f.logins24h() : logins24h, ips7d == null ? Math.max(1, f.distinctIps7d()) : ips7d,
				devices30d == null ? Math.max(1, f.distinctDevices30d()) : devices30d, f.hasSecondStep(),
				f.walletCreatedAt(), false, hops == null ? f.flaggedHops() : hops, 3,
				uaChanged == null ? f.userAgentChanged() : uaChanged);
	}

	private static Instant atHour(Instant day, int hour, SplittableRandom random) {
		return day.atZone(ZoneOffset.UTC).withHour(hour).withMinute(random.nextInt(60)).toInstant();
	}

	/** Scenario name -> how to turn an ordinary sign-in into that attack. */
	private static Map<String, Function<SplittableRandom, RiskFeatures.Facts>> scenarios() {
		Map<String, Function<SplittableRandom, RiskFeatures.Facts>> s = new LinkedHashMap<>();
		s.put("new device and network at an unusual hour", r -> {
			RiskFeatures.Facts f = SyntheticSignIns.benignFacts(r);
			return with(f, atHour(f.now(), 1 + r.nextInt(4), r), false, false, null, null, null, null, null, true);
		});
		s.put("failure burst then success", r -> {
			RiskFeatures.Facts f = SyntheticSignIns.benignFacts(r);
			return with(f, null, false, null, 4L + r.nextInt(12), null, null, null, null, null);
		});
		s.put("many distinct IPs for one wallet", r -> {
			RiskFeatures.Facts f = SyntheticSignIns.benignFacts(r);
			return with(f, null, null, false, null, 5L + r.nextInt(10), 6L + r.nextInt(15), null, null, null);
		});
		s.put("shared device linked to a flagged wallet", r -> {
			RiskFeatures.Facts f = SyntheticSignIns.benignFacts(r);
			return with(f, null, false, null, null, null, null, f.distinctDevices30d() + 1, 1, true);
		});
		return s;
	}

	private double rate(double[] scores) {
		return Arrays.stream(scores).filter(v -> v >= THRESHOLD).count() / (double) scores.length;
	}

	private double[] scores(Function<SplittableRandom, RiskFeatures.Facts> draw, long seed) {
		SplittableRandom random = new SplittableRandom(seed);
		double[] scores = new double[N];
		for (int i = 0; i < N; i++) {
			scores[i] = model.forest().score(RiskFeatures.of(draw.apply(random)));
		}
		return scores;
	}

	/** Probability that a random attack scores higher than a random ordinary sign-in (ties count half). */
	private static double auc(double[] attacks, double[] benign) {
		double[] sorted = benign.clone();
		Arrays.sort(sorted);
		double wins = 0;
		for (double a : attacks) {
			int below = lowerBound(sorted, a);
			int notAbove = lowerBound(sorted, Math.nextUp(a));
			wins += below + (notAbove - below) / 2.0;
		}
		return wins / ((double) attacks.length * benign.length);
	}

	private static int lowerBound(double[] sorted, double value) {
		int lo = 0;
		int hi = sorted.length;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (sorted[mid] < value) {
				lo = mid + 1;
			}
			else {
				hi = mid;
			}
		}
		return lo;
	}

	@Test
	void measuredDetectionAndFalsePositiveRatesOnSyntheticData() {
		double[] benign = scores(SyntheticSignIns::benignFacts, 1234);
		double falsePositives = rate(benign);
		Map<String, String> measured = new LinkedHashMap<>();
		Map<String, Double> detection = new LinkedHashMap<>();
		Map<String, Double> aucs = new LinkedHashMap<>();
		scenarios().forEach((name, draw) -> {
			double[] attack = scores(draw, 99);
			detection.put(name, rate(attack));
			aucs.put(name, auc(attack, benign));
			measured.put(name, String.format("detection %.4f, AUC %.4f", rate(attack), auc(attack, benign)));
		});
		System.out.printf("Synthetic evaluation (threshold %.2f, n=%d each): false-positive rate %.4f; %s%n",
				THRESHOLD, N, falsePositives, measured);

		// Bounds: the values measured on 2026-11-23 (docs/RISK_MODEL.md) minus a margin.
		assertThat(falsePositives).as("false-positive rate on held-out benign data").isLessThanOrEqualTo(0.04);
		assertThat(detection.get("new device and network at an unusual hour")).isGreaterThanOrEqualTo(0.95);
		assertThat(detection.get("shared device linked to a flagged wallet")).isGreaterThanOrEqualTo(0.95);
		// A burst of failures or many addresses moves one or two of fifteen features; an Isolation Forest picks
		// split features at random, so such single-feature outliers are only partly caught. The rules
		// (REPEATED_FAILURES) remain the main defence there.
		assertThat(detection.get("failure burst then success")).isGreaterThanOrEqualTo(0.15);
		assertThat(detection.get("many distinct IPs for one wallet")).isGreaterThanOrEqualTo(0.08);
		aucs.forEach((name, value) -> assertThat(value).as("AUC " + name).isGreaterThanOrEqualTo(0.75));
	}

	@Test
	void attacksScoreHigherThanOrdinarySignInsOnAverage() {
		double benign = Arrays.stream(scores(SyntheticSignIns::benignFacts, 1234)).average().orElseThrow();
		scenarios().forEach((name, draw) -> assertThat(Arrays.stream(scores(draw, 7)).average().orElseThrow())
			.as(name).isGreaterThan(benign));
	}

}
