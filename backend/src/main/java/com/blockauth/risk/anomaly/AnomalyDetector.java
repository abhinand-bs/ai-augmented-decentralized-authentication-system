package com.blockauth.risk.anomaly;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.blockauth.config.AnomalySettings;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds the current anomaly model, scores sign-ins with it, and (re)trains it. Scoring touches no network
 * and no database: it reads one immutable model through an atomic reference. A new model is built off to
 * the side and swapped in whole. Training runs at startup when no model exists and then on a schedule; a
 * PostgreSQL advisory lock makes sure only one instance trains at a time, and every instance picks up the
 * newest stored model. No endpoint can change or retrain the model.
 */
@Component
public class AnomalyDetector {

	public record Status(Integer version, Instant trainedAt, Integer sampleCount, String source,
			AnomalySettings.Mode mode) {
	}

	private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);
	private static final long TRAINING_LOCK = 0x426c6f636b41L; // arbitrary, fixed

	// Recorded ordinary sign-ins: allowed, not from a flagged or flag-linked wallet, at most perWalletCap
	// per wallet (the most recent), newest first.
	private static final String TRAINING_ROWS = """
			select features from (
			  select a.features, a.assessed_at,
			         row_number() over (partition by a.wallet_address order by a.assessed_at desc, a.id desc) as rn
			  from login_assessments a
			  where a.decision = 'ALLOW' and a.features is not null
			    and not exists (select 1 from wallet_flags f where f.wallet_address = a.wallet_address)
			    and coalesce(cast(a.features ->> 'flagged' as double precision), 1) = 0
			    and coalesce(cast(a.features ->> 'flagged_proximity' as double precision), 1) = 0) t
			where rn <= :cap
			order by assessed_at desc
			limit :max
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final AnomalySettings settings;
	private final ObjectMapper json;
	private final Clock clock;
	private final AtomicReference<AnomalyModel> current = new AtomicReference<>();

	public AnomalyDetector(NamedParameterJdbcTemplate jdbc, @NonNull PlatformTransactionManager transactionManager,
			AnomalySettings settings, ObjectMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.settings = settings;
		this.json = json;
		this.clock = clock;
	}

	/** Empty when the model is off or not available; the caller then decides on the rules alone. */
	public Optional<AnomalyModel.Outcome> assess(double[] features) {
		AnomalyModel model = current.get();
		if (settings.mode() == AnomalySettings.Mode.OFF || model == null) {
			return Optional.empty();
		}
		return Optional.of(model.assess(features));
	}

	public Status status() {
		AnomalyModel model = current.get();
		return model == null ? new Status(null, null, null, null, settings.mode())
				: new Status(model.version(), model.trainedAt(), model.sampleCount(), model.source(), settings.mode());
	}

	@EventListener(ApplicationReadyEvent.class)
	public void startup() {
		if (settings.mode() == AnomalySettings.Mode.OFF) {
			return;
		}
		try {
			if (!refresh()) {
				train();
			}
		}
		catch (RuntimeException e) {
			// Sign-ins are scored by the rules alone until a model is available.
			log.error("Anomaly model unavailable at startup: {}", e.getClass().getSimpleName());
		}
	}

	@Scheduled(cron = "${blockauth.security.anomaly.training-schedule:0 30 3 * * *}")
	public void scheduledTraining() {
		if (settings.mode() == AnomalySettings.Mode.OFF) {
			return;
		}
		try {
			train();
		}
		catch (RuntimeException e) {
			log.error("Anomaly model training failed: {}", e.getClass().getSimpleName());
		}
	}

	/** Picks up a newer model trained by another instance. */
	@Scheduled(fixedDelayString = "${blockauth.security.anomaly.refresh-interval:PT5M}",
			initialDelayString = "${blockauth.security.anomaly.refresh-interval:PT5M}")
	public void scheduledRefresh() {
		if (settings.mode() == AnomalySettings.Mode.OFF) {
			return;
		}
		try {
			refresh();
		}
		catch (RuntimeException e) {
			log.warn("Anomaly model refresh failed: {}", e.getClass().getSimpleName());
		}
	}

	/** Loads the newest stored model if it is newer than the one in use. @return whether a model is in use */
	public boolean refresh() {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select version, trained_at, sample_count, source, cast(params as text) as params, trees "
						+ "from risk_models order by version desc limit 1",
				Map.of());
		if (rows.isEmpty()) {
			return current.get() != null;
		}
		Map<String, Object> row = rows.get(0);
		int version = ((Number) row.get("version")).intValue();
		AnomalyModel inUse = current.get();
		if (inUse != null && inUse.version() >= version) {
			return true;
		}
		Map<String, Object> params = json.readValue((String) row.get("params"), new TypeReference<>() {
		});
		if (!RiskFeatures.NAMES.equals(params.get("features"))) {
			log.warn("Stored anomaly model {} has a different feature layout and is ignored", version);
			return inUse != null;
		}
		double[] medians = ((List<?>) params.get("medians")).stream().mapToDouble(v -> ((Number) v).doubleValue())
			.toArray();
		IsolationForest forest = IsolationForest.fromBytes((byte[]) row.get("trees"));
		swap(new AnomalyModel(version, forest, medians, ((Timestamp) row.get("trained_at")).toInstant(),
				((Number) row.get("sample_count")).intValue(), (String) row.get("source")));
		return true;
	}

	/**
	 * Trains a new model and stores it as the next version, unless another instance is training right now.
	 * @return the new version, or empty when another instance held the training lock
	 */
	public Optional<Integer> train() {
		Integer version = transaction.execute(status -> {
			Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(:key)",
					Map.of("key", TRAINING_LOCK), Boolean.class);
			if (!Boolean.TRUE.equals(locked)) {
				return null;
			}
			double[][] real = realSamples();
			boolean enough = real.length >= settings.minRealSamples();
			double[][] data = enough ? real : SyntheticSignIns.benign(settings.syntheticSamples(), settings.seed());
			String source = enough ? "REAL" : "SYNTHETIC";
			IsolationForest forest = IsolationForest.fit(data, settings.trees(), settings.sampleSize(),
					settings.seed());
			double[] medians = AnomalyModel.medians(data);
			int next = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from risk_models", Map.of(),
					Integer.class);
			Instant now = clock.instant();
			Map<String, Object> params = new LinkedHashMap<>();
			params.put("features", RiskFeatures.NAMES);
			params.put("medians", medians);
			params.put("trees", settings.trees());
			params.put("sampleSize", settings.sampleSize());
			params.put("seed", settings.seed());
			jdbc.update("insert into risk_models (version, trained_at, sample_count, source, params, trees) "
					+ "values (:version, :trainedAt, :count, :source, cast(:params as jsonb), :trees)",
					new MapSqlParameterSource().addValue("version", next)
						.addValue("trainedAt", Timestamp.from(now))
						.addValue("count", data.length)
						.addValue("source", source)
						.addValue("params", json.writeValueAsString(params))
						.addValue("trees", forest.toBytes()));
			swap(new AnomalyModel(next, forest, medians, now, data.length, source));
			log.info("Anomaly model {} trained on {} {} sign-ins", next, data.length, source.toLowerCase());
			return next;
		});
		return Optional.ofNullable(version);
	}

	private double[][] realSamples() {
		List<double[]> rows = new ArrayList<>();
		jdbc.query(TRAINING_ROWS,
				new MapSqlParameterSource().addValue("cap", settings.perWalletCap())
					.addValue("max", settings.maxTrainingRows()),
				rs -> {
					Map<String, Number> named = json.readValue(rs.getString("features"), new TypeReference<>() {
					});
					double[] vector = RiskFeatures.fromNamed(named);
					if (vector != null) {
						rows.add(vector);
					}
				});
		return rows.toArray(double[][]::new);
	}

	/** Replaces the model in use, but never with an older one. */
	void swap(AnomalyModel model) {
		current.accumulateAndGet(model, (old, next) -> old == null || next.version() > old.version() ? next : old);
	}

	/** For tests: forget the model in use. */
	public void clear() {
		current.set(null);
	}

}
