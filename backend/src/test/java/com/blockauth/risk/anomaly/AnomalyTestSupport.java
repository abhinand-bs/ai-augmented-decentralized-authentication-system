package com.blockauth.risk.anomaly;

import com.blockauth.config.AnomalySettings;

public final class AnomalyTestSupport {

	private AnomalyTestSupport() {
	}

	/** The documented defaults, in the given mode. */
	public static AnomalySettings settings(AnomalySettings.Mode mode) {
		return new AnomalySettings(mode, 0.6, 30, 200, 50, 20000, 2000, 100, 256, 42, "-");
	}

	/** A model trained on the default synthetic population, as a cold start would build it. */
	public static AnomalyModel coldStartModel(int version) {
		double[][] data = SyntheticSignIns.benign(2000, 42);
		return new AnomalyModel(version, IsolationForest.fit(data, 100, 256, 42), AnomalyModel.medians(data),
				java.time.Instant.parse("2026-11-23T00:00:00Z"), data.length, "SYNTHETIC");
	}

}
