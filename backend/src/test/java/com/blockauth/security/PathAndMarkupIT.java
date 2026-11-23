package com.blockauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/**
 * attacks.md "Path tricks" and the XSS half of "SQL injection and XSS": other spellings of a protected path
 * never reach it, and markup is either refused by validation or stored and returned as plain JSON text.
 */
class PathAndMarkupIT extends IntegrationTest {

	private static final String SCRIPT = "<script>alert(1)</script>";

	@Test
	void otherSpellingsOfAPathAreRefusedBeforeTheyReachAnEndpoint() {
		Api user = registeredAndLoggedIn(new TestWallet());

		// Refused by Spring Security's firewall, with the standard JSON error body.
		for (String path : List.of("/api/users/me;jsessionid=x", "/api/users/%2e%2e/admin/users", "/api//admin/users")) {
			Api.Response response = user.get(path);
			assertThat(response.status()).as(path).isEqualTo(400);
			assertThat(response.code()).as(path).isEqualTo("VALIDATION_FAILED");
		}
		// Refused by Tomcat itself before the application runs: a plain 400 page, not the JSON body.
		for (String path : List.of("/api/admin%2Fusers", "/api/users/..%2Fadmin/users", "/api/admin%5Cusers",
				"/api/users%00/me")) {
			assertThat(user.status(path)).as(path).isEqualTo(400);
		}
		// However the client spells it, a user never gets the admin data.
		assertThat(user.get("/api/users/../admin/users").status()).isIn(400, 403);
	}

	@Test
	void markupIsRefusedByValidationOrKeptAsPlainText() {
		Api admin = registeredAndLoggedIn(ADMIN);
		Map<String, Object> dummyProof = Map.of("challenge", "x", "signature", "0x" + "1".repeat(130));

		Api.Response flag = admin.post("/api/admin/wallet-flags",
				Map.of("walletAddress", new TestWallet().checksumAddress(), "reason", SCRIPT, "proof", dummyProof));
		Api.Response key = admin.post("/api/admin/api-keys",
				Map.of("name", "<b>key</b>", "scopes", List.of("audit:read"), "days", 1, "proof", dummyProof));
		assertThat(flag.status()).isEqualTo(400);
		assertThat(key.status()).isEqualTo(400);

		// A header the client controls is stored as it came and returned as a JSON string.
		TestWallet wallet = new TestWallet();
		Api browser = browser();
		assertThat(register(browser, wallet).status()).isEqualTo(201);
		String challenge = challenge(browser, wallet, "/api/auth/challenge");
		Api.Response login = browser.send("POST", "/api/auth/verify", Api.json(signed(wallet, challenge, wallet)),
				Map.of("User-Agent", SCRIPT), true);
		assertThat(login.status()).isEqualTo(200);

		Api.Response events = admin.get("/api/admin/authentication-events?walletAddress=" + wallet.checksumAddress());
		List<String> agents = new ArrayList<>();
		events.body().get("items").forEach(item -> agents.add(item.get("userAgent").asString()));
		assertThat(agents).contains(SCRIPT);
		assertThat(events.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		assertThat(events.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
	}

}
