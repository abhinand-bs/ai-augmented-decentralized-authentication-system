package com.blockauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/**
 * The other side of {@link ForwardedHeadersIT}: behind a configured proxy (here the test client's own
 * address) the forwarded client address is used, so different clients get their own limits.
 */
@TestPropertySource(properties = { "server.forward-headers-strategy=native",
		"server.tomcat.remoteip.internal-proxies=127.0.0.1/32, ::1/128" })
class TrustedProxyIT extends IntegrationTest {

	@Test
	void aConfiguredProxyPassesOnTheRealClientAddress() {
		Api api = browser();
		api.get("/api/auth/csrf");

		for (int i = 0; i < 11; i++) {
			Api.Response response = api.send("POST", "/api/auth/challenge",
					"{\"walletAddress\":\"" + new TestWallet().checksumAddress() + "\"}",
					Map.of("X-Forwarded-For", "198.51.100." + (i + 1)), true);
			assertThat(response.status()).as("request %d", i + 1).isEqualTo(201);
		}
	}

}
