package com.blockauth.risk.anomaly;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Isolation Forest (Liu, Ting and Zhou, 2008): an unsupervised anomaly detector. Each tree splits a random
 * sample on random features at random values; unusual points are isolated after fewer splits. The score is
 * {@code 2^(-E[h(x)] / c(n))}: near 1 for clear outliers, near 0.5 or below for ordinary points.
 * <p>
 * Deterministic for a given seed, immutable once built, and safe to score from many threads at once. Trees
 * are stored as flat arrays: for node {@code i}, {@code feature[i] < 0} marks a leaf holding
 * {@code size[i]} training points; otherwise the children are {@code left[i]} and {@code left[i] + 1}.
 */
public final class IsolationForest {

	private static final int FORMAT = 1;

	private final int dimensions;
	private final int sampleSize;
	private final Tree[] trees;

	private record Tree(short[] feature, double[] split, int[] left, int[] size) {
	}

	private IsolationForest(int dimensions, int sampleSize, Tree[] trees) {
		this.dimensions = dimensions;
		this.sampleSize = sampleSize;
		this.trees = trees;
	}

	/** @param data rows of equal length; at least two rows */
	public static IsolationForest fit(double[][] data, int treeCount, int sampleSize, long seed) {
		if (data.length < 2 || treeCount < 1) {
			throw new IllegalArgumentException("need at least two rows and one tree");
		}
		int dims = data[0].length;
		int psi = Math.min(sampleSize, data.length);
		int maxDepth = (int) Math.ceil(Math.log(psi) / Math.log(2));
		SplittableRandom random = new SplittableRandom(seed);
		Tree[] trees = new Tree[treeCount];
		for (int t = 0; t < treeCount; t++) {
			int[] sample = sampleWithoutReplacement(data.length, psi, random);
			trees[t] = new Builder(data, dims, random).build(sample, maxDepth);
		}
		return new IsolationForest(dims, psi, trees);
	}

	/** Anomaly score in (0, 1]. */
	public double score(double[] x) {
		if (x.length != dimensions) {
			throw new IllegalArgumentException("expected " + dimensions + " features");
		}
		double total = 0;
		for (Tree tree : trees) {
			total += pathLength(tree, x);
		}
		return Math.pow(2, -(total / trees.length) / averagePath(sampleSize));
	}

	public int dimensions() {
		return dimensions;
	}

	private static double pathLength(Tree tree, double[] x) {
		int node = 0;
		int depth = 0;
		while (tree.feature[node] >= 0) {
			node = x[tree.feature[node]] < tree.split[node] ? tree.left[node] : tree.left[node] + 1;
			depth++;
		}
		return depth + averagePath(tree.size[node]);
	}

	/** c(n): the average path length of an unsuccessful search in a binary search tree of n points. */
	static double averagePath(int n) {
		if (n <= 1) {
			return 0;
		}
		if (n == 2) {
			return 1;
		}
		return 2 * (Math.log(n - 1) + 0.5772156649015329) - 2.0 * (n - 1) / n;
	}

	private static int[] sampleWithoutReplacement(int n, int k, SplittableRandom random) {
		int[] all = new int[n];
		for (int i = 0; i < n; i++) {
			all[i] = i;
		}
		for (int i = 0; i < k; i++) {
			int j = i + random.nextInt(n - i);
			int swap = all[i];
			all[i] = all[j];
			all[j] = swap;
		}
		return Arrays.copyOf(all, k);
	}

	private static final class Builder {

		private final double[][] data;
		private final int dims;
		private final SplittableRandom random;
		private short[] feature = new short[64];
		private double[] split = new double[64];
		private int[] left = new int[64];
		private int[] size = new int[64];
		private int count;

		Builder(double[][] data, int dims, SplittableRandom random) {
			this.data = data;
			this.dims = dims;
			this.random = random;
		}

		Tree build(int[] rows, int maxDepth) {
			int root = allocate(1);
			grow(root, rows, 0, rows.length, 0, maxDepth);
			return new Tree(Arrays.copyOf(feature, count), Arrays.copyOf(split, count), Arrays.copyOf(left, count),
					Arrays.copyOf(size, count));
		}

		/** Splits rows[from, to) at node; rows are partitioned in place. */
		private void grow(int node, int[] rows, int from, int to, int depth, int maxDepth) {
			int n = to - from;
			if (depth >= maxDepth || n <= 1 || !splitNode(node, rows, from, to)) {
				feature[node] = -1;
				size[node] = n;
				return;
			}
			int f = feature[node];
			double s = split[node];
			int mid = from;
			for (int i = from; i < to; i++) {
				if (data[rows[i]][f] < s) {
					int swap = rows[mid];
					rows[mid++] = rows[i];
					rows[i] = swap;
				}
			}
			int children = allocate(2);
			left[node] = children;
			grow(children, rows, from, mid, depth + 1, maxDepth);
			grow(children + 1, rows, mid, to, depth + 1, maxDepth);
		}

		/** Picks a random feature that still varies here and a split strictly inside its range. */
		private boolean splitNode(int node, int[] rows, int from, int to) {
			int start = random.nextInt(dims);
			for (int k = 0; k < dims; k++) {
				int f = (start + k) % dims;
				double min = Double.POSITIVE_INFINITY;
				double max = Double.NEGATIVE_INFINITY;
				for (int i = from; i < to; i++) {
					double v = data[rows[i]][f];
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
				if (max > min) {
					double s = min + random.nextDouble() * (max - min);
					feature[node] = (short) f;
					split[node] = s > min ? s : Math.nextUp(min);
					return true;
				}
			}
			return false;
		}

		private int allocate(int nodes) {
			if (count + nodes > feature.length) {
				int capacity = Math.max(feature.length * 2, count + nodes);
				feature = Arrays.copyOf(feature, capacity);
				split = Arrays.copyOf(split, capacity);
				left = Arrays.copyOf(left, capacity);
				size = Arrays.copyOf(size, capacity);
			}
			int first = count;
			count += nodes;
			return first;
		}

	}

	/** A compact binary form for storage; {@link #fromBytes} restores an identical forest. */
	public byte[] toBytes() {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(FORMAT);
			out.writeInt(dimensions);
			out.writeInt(sampleSize);
			out.writeInt(trees.length);
			for (Tree tree : trees) {
				out.writeInt(tree.feature.length);
				for (int i = 0; i < tree.feature.length; i++) {
					out.writeShort(tree.feature[i]);
					if (tree.feature[i] >= 0) {
						out.writeDouble(tree.split[i]);
						out.writeInt(tree.left[i]);
					}
					else {
						out.writeInt(tree.size[i]);
					}
				}
			}
			out.flush();
			return bytes.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static IsolationForest fromBytes(byte[] stored) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(stored))) {
			if (in.readInt() != FORMAT) {
				throw new IllegalArgumentException("unknown model format");
			}
			int dims = in.readInt();
			int psi = in.readInt();
			Tree[] trees = new Tree[in.readInt()];
			for (int t = 0; t < trees.length; t++) {
				int nodes = in.readInt();
				short[] feature = new short[nodes];
				double[] split = new double[nodes];
				int[] left = new int[nodes];
				int[] size = new int[nodes];
				for (int i = 0; i < nodes; i++) {
					feature[i] = in.readShort();
					if (feature[i] >= dims || (feature[i] < 0 && feature[i] != -1)) {
						throw new IllegalArgumentException("corrupt model");
					}
					if (feature[i] >= 0) {
						split[i] = in.readDouble();
						left[i] = in.readInt();
						if (left[i] <= i || left[i] + 1 >= nodes) {
							throw new IllegalArgumentException("corrupt model");
						}
					}
					else {
						size[i] = in.readInt();
					}
				}
				trees[t] = new Tree(feature, split, left, size);
			}
			return new IsolationForest(dims, psi, trees);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("corrupt model", e);
		}
	}

}
