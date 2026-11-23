package com.blockauth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/**
 * Audit-finding A-03: an audited change must never hold one pooled connection while its audit write waits for
 * a second. With a pool of exactly one connection, such nesting cannot get its second connection, and the audit
 * event would be lost after the (here shortened) connection timeout. Every audited change must still succeed
 * and be audited.
 */
// Flyway locks and migrates on two connections of its own, so it gets its own data source here.
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=1",
		"spring.datasource.hikari.connection-timeout=1500", "spring.flyway.url=${TEST_DB_URL}",
		"spring.flyway.user=${TEST_DB_USERNAME}", "spring.flyway.password=${TEST_DB_PASSWORD}" })
class SinglePoolConnectionIT extends IntegrationTest {

	private final TestWallet wallet = new TestWallet();

	private static String other(JsonNode list) {
		return StreamSupport.stream(list.spliterator(), false)
			.filter(n -> !n.get("current").asBoolean())
			.findFirst()
			.orElseThrow()
			.get("id")
			.asString();
	}

	@Test
	void forgettingADeviceIsAuditedWithASingleConnection() {
		Api laptop = registeredAndLoggedIn(wallet);
		assertThat(login(browser(), wallet).status()).isEqualTo(200);

		Api.Response removed = laptop.post("/api/users/devices/" + other(laptop.get("/api/users/devices").body())
				+ "/remove", null);

		assertThat(removed.status()).isEqualTo(204);
		assertThat(count("select count(*) from authentication_events where event_type = 'DEVICE_REMOVED'"))
			.isEqualTo(1);
	}

	@Test
	void thisWasNotMeIsAuditedWithASingleConnection() {
		Api laptop = registeredAndLoggedIn(wallet);
		assertThat(login(browser(), wallet).status()).isEqualTo(200);
		JsonNode alerts = laptop.get("/api/users/alerts").body();
		assertThat(alerts).hasSize(1);

		Api.Response rejected = laptop.post("/api/users/alerts/" + alerts.get(0).get("id").asLong() + "/sign-out",
				null);

		assertThat(rejected.status()).isEqualTo(204);
		assertThat(count("select count(*) from authentication_events where event_type = 'REMOTE_LOGOUT'"))
			.isEqualTo(1);
	}

}
