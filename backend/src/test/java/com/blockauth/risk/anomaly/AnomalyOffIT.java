package com.blockauth.risk.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/** OFF: the model is never trained or consulted; the features are still stored for a later switch-on. */
@TestPropertySource(properties = "blockauth.security.anomaly.mode=off")
class AnomalyOffIT extends IntegrationTest {

	@Test
	void theRulesAloneDecideAndNoModelExists() {
		TestWallet wallet = new TestWallet();
		registeredAndLoggedIn(wallet);

		Map<String, Object> row = jdbc.queryForMap("select score, rule_score, anomaly_score, model_version, mode, "
				+ "cast(features as text) as features from login_assessments where wallet_address = ?", wallet.address());

		assertThat(row.get("mode")).isEqualTo("OFF");
		assertThat(row.get("anomaly_score")).isNull();
		assertThat(row.get("model_version")).isNull();
		assertThat(row.get("score")).isEqualTo(100);
		assertThat((String) row.get("features")).contains("hour_sin");
		assertThat(count("select count(*) from risk_models")).isZero();
	}

}
