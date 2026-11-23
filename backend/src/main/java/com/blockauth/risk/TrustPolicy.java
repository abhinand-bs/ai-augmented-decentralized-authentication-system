package com.blockauth.risk;

import java.util.ArrayList;
import java.util.List;

import com.blockauth.config.SecuritySettings;

/**
 * The trust score as a pure function of its inputs, so that every outcome can be explained and tested.
 * The score is a risk indicator, never proof of identity: it only decides whether the wallet signature
 * is enough or a second step is asked for as well.
 */
public final class TrustPolicy {

	public enum Rule {
		NEW_DEVICE, NEW_NETWORK, FLAGGED_WALLET, REPEATED_FAILURES, LINKED_TO_FLAGGED, CHECK_UNAVAILABLE,
		/** The learned anomaly model found the sign-in unusual and (in ENFORCE mode) lowered the score. */
		ANOMALY_DETECTED
	}

	public enum Decision {
		/** The wallet signature is enough. */
		ALLOW,
		/** A second step must also pass. */
		SECOND_STEP,
		/** Refused: a second step would be needed, but none is set up. */
		REFUSE
	}

	/**
	 * What the server knows about this sign-in; nothing here comes from the client's own claims.
	 * @param hasHistory whether the wallet has signed in before. Until it has, there is no baseline to
	 * call a device or network "new" against, so those two rules do not apply to a first sign-in.
	 */
	public record Signals(boolean hasHistory, boolean knownDevice, boolean knownNetwork, boolean flaggedWallet,
			long recentFailures, boolean linkedToFlagged) {
	}

	public record Score(int value, List<Rule> rules) {
	}

	private TrustPolicy() {
	}

	public static Score score(Signals signals, SecuritySettings.Trust settings) {
		List<Rule> rules = new ArrayList<>();
		int penalty = 0;
		if (signals.hasHistory() && !signals.knownDevice()) {
			rules.add(Rule.NEW_DEVICE);
			penalty += settings.newDevicePenalty();
		}
		if (signals.hasHistory() && !signals.knownNetwork()) {
			rules.add(Rule.NEW_NETWORK);
			penalty += settings.newNetworkPenalty();
		}
		if (signals.flaggedWallet()) {
			rules.add(Rule.FLAGGED_WALLET);
			penalty += settings.flaggedWalletPenalty();
		}
		if (signals.recentFailures() >= settings.repeatedFailuresThreshold()) {
			rules.add(Rule.REPEATED_FAILURES);
			penalty += settings.repeatedFailuresPenalty();
		}
		if (signals.linkedToFlagged()) {
			rules.add(Rule.LINKED_TO_FLAGGED);
			penalty += settings.linkedToFlaggedPenalty();
		}
		return new Score(Math.max(0, 100 - penalty), List.copyOf(rules));
	}

	/**
	 * Lowers a rule score by the anomaly penalty {@code round(maxPenalty * clamp((a - t) / (1 - t)))}. The
	 * anomaly can only lower a score, never raise it, and the thresholds of {@link #decide} stay as they are.
	 */
	public static Score withAnomaly(Score rules, double anomaly, double threshold, int maxPenalty) {
		int penalty = anomalyPenalty(anomaly, threshold, maxPenalty);
		if (penalty == 0) {
			return rules;
		}
		List<Rule> withRule = new ArrayList<>(rules.rules());
		withRule.add(Rule.ANOMALY_DETECTED);
		return new Score(Math.max(0, rules.value() - penalty), List.copyOf(withRule));
	}

	static int anomalyPenalty(double anomaly, double threshold, int maxPenalty) {
		if (!Double.isFinite(anomaly)) {
			return 0;
		}
		double scaled = Math.max(0, Math.min(1, (anomaly - threshold) / (1 - threshold)));
		return (int) Math.round(maxPenalty * scaled);
	}

	/**
	 * High trust: signature only. Medium: the second step when it is set up. Low: the second step is
	 * required, and a wallet without one is refused rather than let in on the signature alone.
	 */
	public static Decision decide(int score, boolean secondStepEnabled, SecuritySettings.Trust settings) {
		if (score >= settings.allowAt()) {
			return Decision.ALLOW;
		}
		if (secondStepEnabled) {
			return Decision.SECOND_STEP;
		}
		return score >= settings.secondStepAt() ? Decision.ALLOW : Decision.REFUSE;
	}

}
