package com.blockauth.risk;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.blockauth.common.ErrorCode;
import com.blockauth.config.AnomalySettings;
import com.blockauth.config.SecuritySettings;
import com.blockauth.risk.TrustPolicy.Decision;
import com.blockauth.risk.TrustPolicy.Rule;
import com.blockauth.risk.TrustPolicy.Signals;
import com.blockauth.risk.anomaly.AnomalyDetector;
import com.blockauth.risk.anomaly.AnomalyModel;
import com.blockauth.risk.anomaly.RiskFeatures;
import com.blockauth.session.DeviceLabels;

import tools.jackson.databind.ObjectMapper;

/**
 * Scores a sign-in after its signature was verified. All signals come from the server's own records and
 * are read in one query, so the check costs a single database round trip. The rules give the score; the
 * anomaly model is consulted after that query, in memory, and (only in ENFORCE mode) may lower it. Every
 * assessment is stored.
 */
@Service
public class TrustService {

	/** @param failure the error to refuse with when the decision is REFUSE */
	public record Assessment(int score, List<Rule> rules, Decision decision, ErrorCode failure) {
	}

	private static final Logger log = LoggerFactory.getLogger(TrustService.class);
	private static final Duration FAILURE_WINDOW = Duration.ofHours(1);

	// Wallets are linked only through devices both signed in on (never through a shared network, which
	// would link everyone behind one router), breadth-first up to the configured number of hops.
	private static final String SIGNALS = """
			select
			  exists (select 1 from user_sessions s where s.user_id = :userId) as has_history,
			  exists (select 1 from user_devices ud where ud.user_id = :userId and ud.device_id = cast(:deviceId as uuid)
			          and ud.removed_at is null and (not :secondStep or ud.trusted_until > :now)) as known_device,
			  exists (select 1 from user_sessions s where s.user_id = :userId and (s.ip_address = :ip
			          or (:prefix and blockauth_network(s.ip_address) = blockauth_network(:ip)))) as known_network,
			  exists (select 1 from wallet_flags f where f.wallet_address = :wallet) as flagged_wallet,
			  (select count(*) from authentication_events e where e.ip_address = :ip
			          and e.event_status in ('FAILURE', 'BLOCKED') and e.occurred_at > :since) as recent_failures,
			  (
			    with recursive reach (user_id, depth) as (
			      select cast(:userId as uuid), 0
			      union
			      select other.user_id, reach.depth + 1
			      from reach
			      join user_devices mine on mine.user_id = reach.user_id and mine.removed_at is null
			      join user_devices other on other.device_id = mine.device_id and other.removed_at is null
			      where reach.depth < :depth)
			    select min(reach.depth) from reach
			    join users u on u.id = reach.user_id
			    join wallet_flags f on f.wallet_address = u.wallet_address
			    where reach.depth > 0 and reach.user_id <> cast(:userId as uuid)) as flagged_hops,
			  (select max(s.created_at) from user_sessions s where s.user_id = :userId) as last_login_at,
			  (select count(*) from user_sessions s where s.user_id = :userId and s.created_at > :day) as logins_24h,
			  (select count(distinct s.ip_address) from user_sessions s
			          where s.user_id = :userId and s.created_at > :week) as distinct_ips_7d,
			  (select count(distinct s.device_id) from user_sessions s
			          where s.user_id = :userId and s.created_at > :month) as distinct_devices_30d,
			  (select u.created_at from users u where u.id = :userId) as wallet_created_at,
			  (select d.label from user_sessions s join devices d on d.id = s.device_id
			          where s.user_id = :userId order by s.created_at desc limit 1) as last_device_label
			""";

	private record Read(Signals signals, RiskFeatures.Facts facts) {
	}

	private final NamedParameterJdbcTemplate jdbc;
	private final LoginAssessmentRepository assessments;
	private final SecuritySettings settings;
	private final AnomalySettings anomalySettings;
	private final AnomalyDetector anomaly;
	private final ObjectMapper json;
	private final Clock clock;

	public TrustService(NamedParameterJdbcTemplate jdbc, LoginAssessmentRepository assessments,
			SecuritySettings settings, AnomalySettings anomalySettings, AnomalyDetector anomaly, ObjectMapper json,
			Clock clock) {
		this.jdbc = jdbc;
		this.assessments = assessments;
		this.settings = settings;
		this.anomalySettings = anomalySettings;
		this.anomaly = anomaly;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * @param deviceId the device the browser was recognised as, or null for a new browser
	 * @param userAgent the request's User-Agent, compared (as a browser/system family) with the last session's
	 * @param secondStepEnabled whether the wallet has a second step it could be asked for
	 */
	public Assessment assess(UUID userId, String wallet, UUID deviceId, String ip, String userAgent,
			boolean secondStepEnabled) {
		Instant now = clock.instant();
		Assessment assessment;
		double[] features = null;
		Optional<AnomalyModel.Outcome> outcome = Optional.empty();
		int ruleScore = 0;
		try {
			Read read = read(userId, wallet, deviceId, ip, userAgent, secondStepEnabled, now);
			TrustPolicy.Score rules = TrustPolicy.score(read.signals(), settings.trust());
			ruleScore = rules.value();
			features = RiskFeatures.of(read.facts());
			outcome = anomaly(features);
			TrustPolicy.Score blended = anomalySettings.mode() == AnomalySettings.Mode.ENFORCE && outcome.isPresent()
					? TrustPolicy.withAnomaly(rules, outcome.get().score(), anomalySettings.threshold(),
							anomalySettings.maxPenalty())
					: rules;
			Decision decision = TrustPolicy.decide(blended.value(), secondStepEnabled, settings.trust());
			assessment = new Assessment(blended.value(), blended.rules(), decision, ErrorCode.LOGIN_RISK_TOO_HIGH);
		}
		catch (RuntimeException e) {
			// Never let a sign-in through because the check itself failed.
			log.error("Trust score could not be computed: {}", e.getClass().getSimpleName());
			assessment = new Assessment(0, List.of(Rule.CHECK_UNAVAILABLE),
					secondStepEnabled ? Decision.SECOND_STEP : Decision.REFUSE, ErrorCode.RISK_CHECK_UNAVAILABLE);
			features = null;
			outcome = Optional.empty();
		}
		record(wallet, userId, deviceId, ip, assessment, features == null ? null : ruleScore, features, outcome,
				now);
		return assessment;
	}

	/** The model's view, or empty when it is off, not trained yet, or failed: the rules then decide alone. */
	private Optional<AnomalyModel.Outcome> anomaly(double[] features) {
		try {
			return anomaly.assess(features);
		}
		catch (RuntimeException e) {
			log.error("Anomaly model unavailable for this sign-in: {}", e.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private Read read(UUID userId, String wallet, UUID deviceId, String ip, String userAgent, boolean secondStep,
			Instant now) {
		MapSqlParameterSource parameters = new MapSqlParameterSource()
			.addValue("userId", userId, Types.OTHER)
			.addValue("deviceId", deviceId == null ? null : deviceId.toString(), Types.VARCHAR)
			.addValue("secondStep", secondStep)
			.addValue("prefix", settings.trust().networkPrefixMatch())
			.addValue("now", utc(now))
			.addValue("since", utc(now.minus(FAILURE_WINDOW)))
			.addValue("day", utc(now.minus(Duration.ofDays(1))))
			.addValue("week", utc(now.minus(Duration.ofDays(7))))
			.addValue("month", utc(now.minus(Duration.ofDays(30))))
			.addValue("ip", ip, Types.VARCHAR)
			.addValue("wallet", wallet)
			.addValue("depth", settings.trust().graphDepth());
		String currentLabel = DeviceLabels.of(userAgent);
		return jdbc.queryForObject(SIGNALS, parameters, (rs, row) -> {
			Integer hops = (Integer) rs.getObject("flagged_hops");
			Signals signals = new Signals(rs.getBoolean("has_history"), rs.getBoolean("known_device"),
					rs.getBoolean("known_network"), rs.getBoolean("flagged_wallet"), rs.getLong("recent_failures"),
					hops != null);
			String lastLabel = rs.getString("last_device_label");
			RiskFeatures.Facts facts = new RiskFeatures.Facts(now, signals.hasHistory(), signals.knownDevice(),
					signals.knownNetwork(), signals.recentFailures(), instant(rs, "last_login_at"),
					rs.getLong("logins_24h"), rs.getLong("distinct_ips_7d"), rs.getLong("distinct_devices_30d"),
					secondStep, instant(rs, "wallet_created_at"), signals.flaggedWallet(), hops,
					settings.trust().graphDepth(), lastLabel != null && !lastLabel.equals(currentLabel));
			return new Read(signals, facts);
		});
	}

	private void record(String wallet, UUID userId, UUID deviceId, String ip, Assessment assessment, Integer ruleScore,
			double[] features, Optional<AnomalyModel.Outcome> outcome, Instant now) {
		try {
			List<String> top = outcome.map(AnomalyModel.Outcome::topFeatures).orElse(List.of());
			assessments.save(new LoginAssessment(wallet, userId, deviceId, ip, assessment.score(),
					assessment.rules().stream().map(Rule::name).collect(Collectors.joining(",")),
					assessment.decision(), now, features == null ? null : json.writeValueAsString(RiskFeatures.named(features)),
					ruleScore, outcome.map(o -> Math.round(o.score() * 10_000) / 10_000.0).orElse(null),
					assessment.score(), outcome.map(AnomalyModel.Outcome::version).orElse(null),
					features == null ? null : anomalySettings.mode().name(),
					top.isEmpty() ? null : String.join(",", new ArrayList<>(top))));
		}
		catch (RuntimeException e) {
			log.error("Failed to record a trust assessment: {}", e.getClass().getSimpleName());
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
