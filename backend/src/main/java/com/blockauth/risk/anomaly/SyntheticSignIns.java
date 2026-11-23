package com.blockauth.risk.anomaly;

import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;

/**
 * A made-up population of ordinary sign-ins, used to train the first model before enough real sign-ins
 * have been recorded (and by the evaluation tests). It encodes assumptions, not observations: daytime
 * activity, mostly known devices and networks, few failures, a handful of sign-ins a day. A model trained
 * on it says only how far a sign-in is from these assumptions.
 */
public final class SyntheticSignIns {

	private static final Instant EPOCH = Instant.parse("2026-01-05T00:00:00Z"); // a Monday

	private SyntheticSignIns() {
	}

	public static double[][] benign(int count, long seed) {
		SplittableRandom random = new SplittableRandom(seed);
		double[][] rows = new double[count][];
		for (int i = 0; i < count; i++) {
			rows[i] = RiskFeatures.of(benignFacts(random));
		}
		return rows;
	}

	/** One ordinary sign-in. Public so tests can build attack variants from the same baseline. */
	public static RiskFeatures.Facts benignFacts(SplittableRandom random) {
		int day = random.nextInt(10) < 8 ? random.nextInt(5) : 5 + random.nextInt(2);
		double hour = clamp(gaussian(random, 14, 3.5), 6, 23.5);
		Instant now = EPOCH.plus(Duration.ofDays(day + 7L * random.nextInt(50)))
			.plus(Duration.ofMinutes((long) (hour * 60)));
		boolean hasHistory = random.nextInt(100) < 97;
		boolean newDevice = hasHistory && random.nextInt(100) < 6;
		boolean newNetwork = hasHistory && random.nextInt(100) < 15;
		long failures = random.nextInt(100) < 92 ? 0 : 1 + random.nextInt(2);
		double minutesSince = Math.exp(gaussian(random, Math.log(24 * 60), 1.2));
		Instant last = hasHistory ? now.minus(Duration.ofMinutes((long) Math.max(5, minutesSince))) : null;
		long logins24h = hasHistory ? random.nextInt(100) < 60 ? random.nextInt(2) : 1 + random.nextInt(3) : 0;
		long ips7d = hasHistory ? 1 + (random.nextInt(100) < 30 ? random.nextInt(2) + 1 : 0) : 0;
		long devices30d = hasHistory ? 1 + (random.nextInt(100) < 20 ? 1 : 0) : 0;
		Instant created = now.minus(Duration.ofHours(24 + random.nextInt(24 * 365)));
		boolean uaChanged = hasHistory && random.nextInt(100) < 4;
		return new RiskFeatures.Facts(now, hasHistory, !newDevice, !newNetwork, failures, last, logins24h, ips7d,
				devices30d, random.nextInt(100) < 30, created, false, null, 3, uaChanged);
	}

	static double gaussian(SplittableRandom random, double mean, double sd) {
		// Box-Muller; SplittableRandom has no nextGaussian.
		double u = 1 - random.nextDouble();
		double v = random.nextDouble();
		return mean + sd * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

}
