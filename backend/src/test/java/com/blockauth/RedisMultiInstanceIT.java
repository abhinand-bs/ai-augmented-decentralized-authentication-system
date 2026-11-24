package com.blockauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.blockauth.config.SecuritySettings;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.blockauth.secondstep.SecondStepTestSupport;

/**
 * Two instances sharing Redis and the database. Instance A is the usual test application (with the stand-in
 * chain); instance B is a second, real application context started here on its own port. Needs a Redis at
 * REDIS_HOST/REDIS_PORT with REDIS_PASSWORD (a container in CI, see docs). Tagged "redis".
 */
@Tag("redis")
@ActiveProfiles({ "test", "redis" })
class RedisMultiInstanceIT extends IntegrationTest {

	@Autowired
	private SecuritySettings settings;

	@Autowired
	private StringRedisTemplate redis;

	private final List<ConfigurableApplicationContext> started = new ArrayList<>();

	@AfterEach
	void stopInstances() {
		started.forEach(ConfigurableApplicationContext::close);
		started.clear();
	}

	/** Another instance of the application on the same database and Redis (or, with redisPort, another Redis). */
	private Api instance(Integer redisPort) {
		Map<String, Object> props = new java.util.HashMap<>(Map.of("server.port", 0,
				"blockauth.admin-wallets", ADMIN.checksumAddress(),
				"blockauth.blockchain.operator-private-key", "0x" + "11".repeat(32),
				"blockauth.security.second-step-key", settings.secondStepKey(),
				"blockauth.security.anomaly.mode", "off"));
		if (redisPort != null) {
			props.put("spring.data.redis.port", redisPort);
		}
		// As command-line arguments: they outrank application*.yml and the developer's .env.local, which plain
		// builder properties (the lowest precedence) would not.
		String[] args = props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
		ConfigurableApplicationContext context = new SpringApplicationBuilder(BlockAuthApplication.class)
			.profiles("test", "redis").run(args);
		started.add(context);
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		return new Api("http://localhost:" + port);
	}

	private static Api withCookiesOf(Api source, Api target) {
		target.cookies.putAll(source.cookies);
		return target;
	}

	@Test
	void aSessionStartedOnOneInstanceWorksOnTheOtherAndSurvivesItsRestart() {
		TestWallet wallet = new TestWallet();
		Api onA = registeredAndLoggedIn(wallet);
		Api onB = withCookiesOf(onA, instance(null));

		assertThat(onB.get("/api/users/me").status()).isEqualTo(200);

		// A fresh instance (as after a restart) still knows the session, and closed nothing at startup.
		stopInstances();
		Api onC = withCookiesOf(onA, instance(null));
		assertThat(onC.get("/api/users/me").status()).isEqualTo(200);
		assertThat(count("select count(*) from user_sessions where end_reason = 'RESTART'")).isZero();
	}

	@Test
	void revokingOnOneInstanceEndsTheSessionOnTheOther() {
		TestWallet wallet = new TestWallet();
		Api onA = registeredAndLoggedIn(wallet);
		Api onB = withCookiesOf(onA, instance(null));
		Api admin = registeredAndLoggedIn(ADMIN);

		assertThat(admin.post("/api/admin/wallet/" + wallet.checksumAddress() + "/revoke",
				proof(admin, ADMIN, "REVOKE_WALLET", wallet)).status()).isEqualTo(200);

		assertThat(onB.get("/api/users/me").code()).isEqualTo("SESSION_REVOKED");
	}

	@Test
	void aPendingSecondStepCompletesOnTheOtherInstance() {
		TestWallet wallet = new TestWallet();
		Api laptop = registeredAndLoggedIn(wallet);
		SecondStepTestSupport.Enabled enabled = SecondStepTestSupport.enable(laptop, clock.instant(),
				action -> proof(laptop, wallet, action, Map.of()));
		Api phoneOnA = browser();
		assertThat(login(phoneOnA, wallet).status()).isEqualTo(202);

		Api phoneOnB = withCookiesOf(phoneOnA, instance(null));
		// A backup code: the current authenticator code was already used to turn the second step on, and each code
		// works once.
		Api.Response done = phoneOnB.post("/api/auth/second-step", Map.of("code", enabled.backupCodes().get(0)));

		assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
		assertThat(withCookiesOf(phoneOnB, browser()).get("/api/users/me").status()).isEqualTo(200);
	}

	@Test
	void rateLimitsAreSharedBetweenInstances() {
		Api onB = instance(null);
		TestWallet wallet = new TestWallet();
		for (int i = 0; i < 5; i++) {
			assertThat(browser().post("/api/auth/challenge", Map.of("walletAddress", new TestWallet().checksumAddress()))
				.status()).isEqualTo(201);
			assertThat(onB.post("/api/auth/challenge", Map.of("walletAddress", new TestWallet().checksumAddress()))
				.status()).isEqualTo(201);
		}

		Api.Response eleventh = onB.post("/api/auth/challenge", Map.of("walletAddress", wallet.checksumAddress()));

		assertThat(eleventh.status()).isEqualTo(429);
		assertThat(eleventh.headers().firstValue("Retry-After")).isPresent();
	}

	@Test
	void withRedisDownSessionsFailClosedButLimitsStillApplyAndHealthAnswers() {
		TestWallet wallet = new TestWallet();
		Api onA = registeredAndLoggedIn(wallet);
		Api down = withCookiesOf(onA, instance(1)); // nothing listens on port 1

		Api.Response me = down.get("/api/users/me");
		assertThat(me.status()).isEqualTo(503);
		assertThat(me.code()).isEqualTo("DATABASE_UNAVAILABLE");
		assertThat(me.body().toString()).doesNotContain("Exception").doesNotContain("redis");
		// A health probe carries no session cookie, so it never touches the session store.
		Api fresh = new Api(down.baseUrl());
		assertThat(fresh.get("/actuator/health").status()).isEqualTo(200);
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			statuses.add(fresh.post("/api/auth/challenge", Map.of("walletAddress", new TestWallet().checksumAddress()))
				.status());
		}
		// The in-memory fallback still limits: never a moment without limits.
		assertThat(statuses).contains(429);
	}

	/**
	 * Finding A-35 (live test): a request still running during a logout can leave a half-written session in Redis.
	 * Such a session must count as no session (401), never as a server error (500), and must not block a new
	 * sign-in.
	 */
	@Test
	void aHalfWrittenSessionCountsAsNoSession() {
		TestWallet wallet = new TestWallet();
		Api api = registeredAndLoggedIn(wallet);
		String id = new String(java.util.Base64.getDecoder().decode(api.cookies.get("BLOCKAUTH_SESSION")),
				java.nio.charset.StandardCharsets.UTF_8);
		String key = "blockauth:session:sessions:" + id;
		assertThat(redis.hasKey(key)).isTrue();
		redis.opsForHash().delete(key, "creationTime");

		Api.Response me = api.get("/api/users/me");

		assertThat(me.status()).as(me.body().toString()).isEqualTo(401);
		assertThat(redis.hasKey(key)).isFalse();
		rateLimiter.clear();
		assertThat(login(api, wallet).status()).isEqualTo(200);
	}

	/** Found in the live test (A-37): with Redis sessions an API-key request failed (403 after a 500). */
	@Test
	void anApiKeyWorksWithRedisSessionsAndOpensNoSession() {
		Api admin = registeredAndLoggedIn(ADMIN);
		Map<String, Object> parameters = Map.of("name", "Monitor", "scopes", List.of("audit:read"), "days", 1);
		Map<String, Object> body = new java.util.LinkedHashMap<>(proof(admin, ADMIN, "CREATE_API_KEY", parameters));
		body.putAll(parameters);
		String token = admin.post("/api/admin/api-keys", body).body().get("token").asString();

		Api.Response read = browser().send("GET", "/api/admin/statistics", null,
				Map.of(com.blockauth.apikey.ApiKeyFilter.HEADER, token), false);

		assertThat(read.status()).isEqualTo(200);
		assertThat(read.setCookies()).noneMatch(c -> c.startsWith("BLOCKAUTH_SESSION="));
	}

}
