package com.blockauth.risk.anomaly;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers the anomaly model sees for one sign-in. They are derived from the server's own records; no IP
 * address, user agent or other raw identifier is part of them, so storing them reveals nothing a person
 * could be identified by. Times are UTC.
 */
public final class RiskFeatures {

	/** Feature names, in vector order. Stored with every model, so a model never meets a different layout. */
	public static final List<String> NAMES = List.of("hour_sin", "hour_cos", "day_of_week", "new_device",
			"new_network", "recent_failures", "minutes_since_last_login", "logins_24h", "distinct_ips_7d",
			"distinct_devices_30d", "has_second_step", "wallet_age_days", "flagged", "flagged_proximity",
			"user_agent_changed");

	/** Used for "minutes since the last sign-in" when there was none: about two years. */
	static final double NEVER_MINUTES = 1_000_000;

	/**
	 * Everything the one signals query returns for this sign-in.
	 * @param flaggedHops shortest number of shared-device hops to a flagged wallet, or null when none
	 * within {@code maxHops}
	 */
	public record Facts(Instant now, boolean hasHistory, boolean knownDevice, boolean knownNetwork,
			long recentFailures, Instant lastLoginAt, long logins24h, long distinctIps7d, long distinctDevices30d,
			boolean hasSecondStep, Instant walletCreatedAt, boolean flagged, Integer flaggedHops, int maxHops,
			boolean userAgentChanged) {
	}

	private RiskFeatures() {
	}

	public static double[] of(Facts f) {
		ZonedDateTime utc = f.now().atZone(ZoneOffset.UTC);
		double hour = utc.getHour() + utc.getMinute() / 60.0;
		double angle = 2 * Math.PI * hour / 24;
		double minutesSince = f.lastLoginAt() == null ? NEVER_MINUTES
				: Math.max(0, Duration.between(f.lastLoginAt(), f.now()).toMinutes());
		double ageDays = f.walletCreatedAt() == null ? 0
				: Math.max(0, Duration.between(f.walletCreatedAt(), f.now()).toHours() / 24.0);
		double proximity = f.flaggedHops() == null || f.flaggedHops() < 1 ? 0
				: (f.maxHops() + 1.0 - f.flaggedHops()) / f.maxHops();
		return new double[] { Math.sin(angle), Math.cos(angle), dayIndex(utc.getDayOfWeek()),
				bit(f.hasHistory() && !f.knownDevice()), bit(f.hasHistory() && !f.knownNetwork()),
				Math.log1p(f.recentFailures()), Math.log1p(minutesSince), f.logins24h(), f.distinctIps7d(),
				f.distinctDevices30d(), bit(f.hasSecondStep()), Math.log1p(ageDays), bit(f.flagged()), proximity,
				bit(f.userAgentChanged()) };
	}

	/** name -> value, rounded to 4 decimals, for storage and display. */
	public static Map<String, Double> named(double[] vector) {
		Map<String, Double> named = new LinkedHashMap<>();
		for (int i = 0; i < NAMES.size(); i++) {
			named.put(NAMES.get(i), Math.round(vector[i] * 10_000) / 10_000.0);
		}
		return named;
	}

	/** The vector from a stored name -> value map; null when a feature is missing. */
	public static double[] fromNamed(Map<String, ? extends Number> named) {
		double[] vector = new double[NAMES.size()];
		for (int i = 0; i < NAMES.size(); i++) {
			Number value = named.get(NAMES.get(i));
			if (value == null || !Double.isFinite(value.doubleValue())) {
				return null;
			}
			vector[i] = value.doubleValue();
		}
		return vector;
	}

	private static double dayIndex(DayOfWeek day) {
		return day.getValue() - 1;
	}

	private static double bit(boolean value) {
		return value ? 1 : 0;
	}

}
