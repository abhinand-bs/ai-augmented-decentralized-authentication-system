package com.blockauth.risk.anomaly;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * One trained, versioned model: the forest plus each feature's median over the training data, which the
 * explanation uses as the "ordinary" value of a feature.
 * @param source SYNTHETIC (cold start) or REAL (recorded ordinary sign-ins)
 */
public record AnomalyModel(int version, IsolationForest forest, double[] medians, Instant trainedAt,
		int sampleCount, String source) {

	/** The score and the (at most two) features that raise it most. */
	public record Outcome(double score, List<String> topFeatures, int version) {
	}

	public static double[] medians(double[][] rows) {
		int dims = rows[0].length;
		double[] medians = new double[dims];
		double[] column = new double[rows.length];
		for (int f = 0; f < dims; f++) {
			for (int i = 0; i < rows.length; i++) {
				column[i] = rows[i][f];
			}
			Arrays.sort(column);
			int mid = column.length / 2;
			medians[f] = column.length % 2 == 1 ? column[mid] : (column[mid - 1] + column[mid]) / 2;
		}
		return medians;
	}

	/**
	 * Leave-one-feature-out: each feature in turn is replaced by its training median and the sign-in scored
	 * again. The features whose replacement lowers the score most are the ones that made it look unusual.
	 */
	public Outcome assess(double[] x) {
		double score = forest.score(x);
		record Drop(String name, double drop) {
		}
		List<Drop> drops = new ArrayList<>();
		double[] probe = x.clone();
		for (int f = 0; f < x.length; f++) {
			if (x[f] == medians[f]) {
				continue;
			}
			probe[f] = medians[f];
			double drop = score - forest.score(probe);
			probe[f] = x[f];
			if (drop > 0) {
				drops.add(new Drop(RiskFeatures.NAMES.get(f), drop));
			}
		}
		List<String> top = drops.stream()
			.sorted(Comparator.comparingDouble(Drop::drop).reversed().thenComparing(Drop::name))
			.limit(2)
			.map(Drop::name)
			.toList();
		return new Outcome(score, top, version);
	}

}
