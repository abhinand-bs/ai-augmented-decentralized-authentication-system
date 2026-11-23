package com.blockauth.risk.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/** The anomaly model in its default mode, SHADOW: computed and stored for every sign-in, never deciding. */
class AnomalyShadowIT extends IntegrationTest {

	@Autowired
	private AnomalyDetector detector;

	@AfterEach
	void restoreModel() {
		detector.clear();
		detector.train();
	}

	private Map<String, Object> lastAssessment(TestWallet wallet) {
		return jdbc.queryForMap("select score, rule_score, final_score, anomaly_score, model_version, mode, rules, "
				+ "decision, cast(features as text) as features, top_features from login_assessments "
				+ "where wallet_address = ? order by id desc limit 1", wallet.address());
	}

	@Test
	void everySignInIsScoredAndStoredButTheRulesDecide() {
		TestWallet wallet = new TestWallet();
		registeredAndLoggedIn(wallet);
		assertThat(login(browser(), wallet).status()).isEqualTo(200); // new device: rule score 70, allowed

		Map<String, Object> row = lastAssessment(wallet);

		assertThat(row.get("mode")).isEqualTo("SHADOW");
		assertThat(row.get("rule_score")).isEqualTo(70);
		assertThat(row.get("final_score")).isEqualTo(70);
		assertThat(row.get("score")).isEqualTo(70);
		assertThat(row.get("decision")).isEqualTo("ALLOW");
		assertThat((String) row.get("rules")).doesNotContain("ANOMALY_DETECTED");
		assertThat((Double) row.get("anomaly_score")).isBetween(0.0, 1.0);
		assertThat(row.get("model_version")).isNotNull();
		// Only numbers by feature name: no address, no user agent.
		String features = (String) row.get("features");
		assertThat(features).contains("\"new_device\": 1.0").doesNotContain("127.0.0.1").doesNotContain("Java");
	}

	@Test
	void aBrokenModelFallsBackToTheRules() {
		// A model built for two features cannot score fifteen: every call throws.
		double[][] two = { { 0, 0 }, { 1, 1 }, { 2, 2 } };
		detector.swap(new AnomalyModel(1_000_000, IsolationForest.fit(two, 5, 3, 1), new double[] { 1, 1 },
				Instant.now(), 3, "SYNTHETIC"));
		TestWallet wallet = new TestWallet();

		Api api = registeredAndLoggedIn(wallet);

		assertThat(api.get("/api/users/me").status()).isEqualTo(200);
		Map<String, Object> row = lastAssessment(wallet);
		assertThat(row.get("anomaly_score")).isNull();
		assertThat(row.get("model_version")).isNull();
		assertThat(row.get("rule_score")).isEqualTo(100);
		assertThat(row.get("decision")).isEqualTo("ALLOW");
	}

	@Test
	void withoutAModelTheRulesDecideAlone() {
		detector.clear();
		TestWallet wallet = new TestWallet();

		registeredAndLoggedIn(wallet);

		assertThat(lastAssessment(wallet).get("anomaly_score")).isNull();
		assertThat(lastAssessment(wallet).get("decision")).isEqualTo("ALLOW");
	}

	@Test
	void trainingWhileSigningInChangesNoOutcome() throws Exception {
		List<TestWallet> wallets = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			TestWallet w = new TestWallet();
			assertThat(register(browser(), w).status()).isEqualTo(201);
			wallets.add(w);
		}
		// The registrations above used this address's challenge allowance (10 a minute); start the minute afresh.
		rateLimiter.clear();
		AtomicBoolean done = new AtomicBoolean();
		AtomicInteger trained = new AtomicInteger();
		CompletableFuture<Void> training = CompletableFuture.runAsync(() -> {
			while (!done.get()) {
				detector.train().ifPresent(v -> trained.incrementAndGet());
			}
		});
		List<Supplier<Api.Response>> logins = new ArrayList<>();
		for (TestWallet w : wallets) {
			logins.add(() -> login(browser(), w));
		}
		List<Api.Response> responses = concurrently(logins);
		done.set(true);
		training.get();

		assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
		assertThat(trained.get()).isPositive();
		assertThat(count("select count(*) from login_assessments where model_version is not null")).isEqualTo(6);
		assertThat(count("select count(distinct version) from risk_models")).isEqualTo(trained.get());
	}

	@Test
	void administratorsSeeTheModelFieldsAndStatusButCannotChangeIt() {
		TestWallet wallet = new TestWallet();
		registeredAndLoggedIn(wallet);
		Api admin = registeredAndLoggedIn(ADMIN);

		Api.Response page = admin.get("/api/admin/login-assessments?minAnomaly=0&walletAddress=" + wallet.checksumAddress());
		Api.Response status = admin.get("/api/admin/risk-model");

		assertThat(page.status()).isEqualTo(200);
		assertThat(page.body().get("items")).hasSize(1);
		assertThat(page.body().get("items").get(0).get("mode").asString()).isEqualTo("SHADOW");
		assertThat(admin.get("/api/admin/login-assessments?minAnomaly=1.01").code()).isEqualTo("VALIDATION_FAILED");
		assertThat(admin.get("/api/admin/login-assessments?minAnomaly=0.999&walletAddress="
				+ wallet.checksumAddress()).body().get("items")).isEmpty();
		assertThat(status.status()).isEqualTo(200);
		assertShape(status.body(), "version", "trainedAt", "sampleCount", "source", "mode");
		assertThat(status.body().get("source").asString()).isEqualTo("SYNTHETIC");
		// No write endpoint exists for the model.
		assertThat(admin.post("/api/admin/risk-model", Map.of()).status()).isIn(403, 405);
	}

}
