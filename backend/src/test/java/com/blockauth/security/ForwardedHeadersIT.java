package com.blockauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/**
 * attacks.md "Rate-limit evasion": with forwarded headers switched on (the prod profile), X-Forwarded-For
 * from a sender that is not a configured proxy must not change the client address. The tests reach the
 * server from 127.0.0.1, so trusting only 10.0.0.0/8 makes the test client an untrusted sender.
 */
@TestPropertySource(properties = { "server.forward-headers-strategy=native",
		"server.tomcat.remoteip.internal-proxies=10.0.0.0/8" })
class ForwardedHeadersIT extends IntegrationTest {

	@Test
	void anUntrustedSenderCannotChooseItsAddressWithForwardedHeaders() {
		Api api = browser();
		api.get("/api/auth/csrf");
		for (int i = 0; i < 10; i++) {
			assertThat(challenge(api, i).status()).isEqualTo(201);
		}

		Api.Response eleventh = challenge(api, 10);

		assertThat(eleventh.status()).isEqualTo(429);
		assertThat(count("select count(*) from authentication_events where ip_address like '198.51.100.%'"))
				.isZero();
	}

	@Test
	void onlyTheLocalMachineIsTrustedUnlessTrustedProxiesIsSet() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));

		assertThat(yaml.getObject().getProperty("server.tomcat.remoteip.internal-proxies"))
				.isEqualTo("${TRUSTED_PROXIES:127.0.0.1/32, ::1/128}");
	}

	private static Api.Response challenge(Api api, int n) {
		return api.send("POST", "/api/auth/challenge",
				"{\"walletAddress\":\"" + new TestWallet().checksumAddress() + "\"}",
				Map.of("X-Forwarded-For", "198.51.100." + (n + 1)), true);
	}

}
