package com.blockauth.risk.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class IsolationForestTest {

	/** 500 points around the origin in two dimensions, plus three far-away points. */
	private static double[][] clusterWithOutliers() {
		SplittableRandom random = new SplittableRandom(7);
		double[][] data = new double[503][];
		for (int i = 0; i < 500; i++) {
			data[i] = new double[] { SyntheticSignIns.gaussian(random, 0, 1), SyntheticSignIns.gaussian(random, 0, 1) };
		}
		data[500] = new double[] { 8, 8 };
		data[501] = new double[] { -9, 7 };
		data[502] = new double[] { 0, -10 };
		return data;
	}

	@Test
	void knownOutliersScoreHigherThanEveryOrdinaryPoint() {
		double[][] data = clusterWithOutliers();
		IsolationForest forest = IsolationForest.fit(data, 100, 256, 1);

		double maxOrdinary = Arrays.stream(data, 0, 500).mapToDouble(forest::score).max().orElseThrow();
		for (int i = 500; i < 503; i++) {
			assertThat(forest.score(data[i])).isGreaterThan(maxOrdinary).isGreaterThan(0.6);
		}
		assertThat(forest.score(new double[] { 0, 0 })).isLessThan(0.5);
	}

	@Test
	void theSameSeedBuildsTheSameForest() {
		double[][] data = clusterWithOutliers();
		IsolationForest a = IsolationForest.fit(data, 50, 128, 99);
		IsolationForest b = IsolationForest.fit(data, 50, 128, 99);

		assertThat(a.toBytes()).isEqualTo(b.toBytes());
		assertThat(a.score(data[3])).isEqualTo(b.score(data[3]));
		assertThat(IsolationForest.fit(data, 50, 128, 100).toBytes()).isNotEqualTo(a.toBytes());
	}

	@Test
	void aStoredForestScoresExactlyLikeTheOriginal() {
		double[][] data = clusterWithOutliers();
		IsolationForest forest = IsolationForest.fit(data, 100, 256, 5);

		IsolationForest restored = IsolationForest.fromBytes(forest.toBytes());

		for (double[] row : data) {
			assertThat(restored.score(row)).isEqualTo(forest.score(row));
		}
	}

	@Test
	void aCorruptStoredForestIsRefusedNotTrusted() {
		byte[] bytes = IsolationForest.fit(clusterWithOutliers(), 3, 16, 5).toBytes();

		assertThatThrownBy(() -> IsolationForest.fromBytes(Arrays.copyOf(bytes, bytes.length / 2)))
			.isInstanceOf(IllegalArgumentException.class);
		byte[] wrongFormat = bytes.clone();
		wrongFormat[3] = 9;
		assertThatThrownBy(() -> IsolationForest.fromBytes(wrongFormat)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void constantDataAndWrongShapesAreHandled() {
		double[][] constant = new double[50][];
		Arrays.setAll(constant, i -> new double[] { 1, 1 });
		IsolationForest forest = IsolationForest.fit(constant, 10, 32, 1);

		assertThat(forest.score(new double[] { 1, 1 })).isBetween(0.0, 1.0);
		assertThatThrownBy(() -> forest.score(new double[] { 1 })).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IsolationForest.fit(new double[][] { { 1 } }, 10, 32, 1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void averagePathMatchesThePaper() {
		assertThat(IsolationForest.averagePath(1)).isZero();
		assertThat(IsolationForest.averagePath(2)).isEqualTo(1);
		// c(256) = 2 H(255) - 2 * 255 / 256, with H(i) ~ ln(i) + 0.5772
		assertThat(IsolationForest.averagePath(256)).isCloseTo(10.2448, org.assertj.core.data.Offset.offset(0.001));
	}

}
