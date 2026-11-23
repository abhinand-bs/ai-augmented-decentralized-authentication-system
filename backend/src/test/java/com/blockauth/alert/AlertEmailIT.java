package com.blockauth.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.blockauth.Api;
import com.blockauth.IntegrationTest;
import com.blockauth.TestWallet;

/** E-mail alerts with the mail server replaced by a mock that records every message. */
// Two alert mails an hour here (three by default), so the limit is reached within the per-wallet challenge limit.
@TestPropertySource(properties = { "blockauth.mail.enabled=true", "blockauth.mail.from=alerts@blockauth.test",
		"blockauth.mail.max-alerts-per-hour=2" })
class AlertEmailIT extends IntegrationTest {

	@MockitoBean
	private JavaMailSender mail;

	private final TestWallet wallet = new TestWallet();

	@BeforeEach
	void resetMail() {
		reset(mail);
	}

	private List<SimpleMailMessage> sent(int atLeast) {
		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			verify(mail, atLeastOnce()).send(captor.capture());
			assertThat(captor.getAllValues()).hasSizeGreaterThanOrEqualTo(atLeast);
		});
		return captor.getAllValues();
	}

	private Api.Response setAddress(Api api, String email) {
		Map<String, Object> body = new java.util.LinkedHashMap<>(proof(api, wallet, "SET_ALERT_EMAIL", Map.of("email", email)));
		body.put("email", email);
		return api.post("/api/users/alert-email", body);
	}

	private static String code(SimpleMailMessage message) {
		Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(message.getText());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	/** Signed in, with a verified alert address. */
	private Api withVerifiedAddress(String email) {
		Api laptop = registeredAndLoggedIn(wallet);
		assertThat(setAddress(laptop, email).status()).isEqualTo(200);
		String code = code(sent(1).get(0));
		Api.Response verified = laptop.post("/api/users/alert-email/verify", Map.of("code", code));
		assertThat(verified.status()).as(verified.body().toString()).isEqualTo(200);
		assertThat(verified.body().get("verified").asBoolean()).isTrue();
		reset(mail);
		return laptop;
	}

	@Test
	void anAddressIsVerifiedWithAMailedCodeAndStoredEncrypted() {
		Api laptop = registeredAndLoggedIn(wallet);

		Api.Response set = setAddress(laptop, "Owner@Example.com");

		assertThat(set.status()).isEqualTo(200);
		assertShape(set.body(), "available", "address", "verified", "codeExpiresAt");
		assertThat(set.body().get("address").asString()).isEqualTo("o***@example.com");
		assertThat(set.body().get("verified").asBoolean()).isFalse();
		SimpleMailMessage mailed = sent(1).get(0);
		assertThat(mailed.getTo()).containsExactly("owner@example.com");
		assertThat(mailed.getSubject()).isEqualTo(AlertMailer.VERIFY_SUBJECT);
		assertThat(jdbc.queryForObject("select address_ciphertext from alert_emails", String.class))
			.doesNotContain("example");
		assertThat(jdbc.queryForObject("select code_hash from alert_emails", String.class)).doesNotContain(code(mailed));
		assertThat(laptop.post("/api/users/alert-email/verify", Map.of("code", "000000")).code())
			.isIn("VERIFICATION_CODE_INVALID");
		assertThat(laptop.post("/api/users/alert-email/verify", Map.of("code", code(mailed))).status()).isEqualTo(200);
	}

	@Test
	void aSignInOnANewDeviceIsMailedWithCoarseDataAndNoLinks() {
		withVerifiedAddress("owner@example.com");

		assertThat(login(browser(), wallet).status()).isEqualTo(200);

		SimpleMailMessage alert = sent(1).get(0);
		assertThat(alert.getSubject()).isEqualTo(AlertMailer.SIGN_IN_SUBJECT);
		assertThat(alert.getText()).contains("If this was not you, open BlockAuth and choose This was not me.")
			.contains("UTC").contains("127.0.0.x").doesNotContain("http").doesNotContain("127.0.0.1");
		// The verification code mail and this alert.
		await().atMost(Duration.ofSeconds(5)).until(() -> count(
				"select count(*) from authentication_events where event_type = 'ALERT_EMAIL_SENT'") == 2);
	}

	@Test
	void headerInjectionIsRefusedAndClientTextCannotAddLines() {
		Api laptop = registeredAndLoggedIn(wallet);
		Api.Response injected = laptop.post("/api/users/alert-email/verify", Map.of("code", "1"));
		assertThat(injected.code()).isEqualTo("ALERT_EMAIL_NOT_PENDING");
		for (String bad : List.of("a@b.com\r\nBcc: x@evil.test", "a@b.com\nSubject: hi", "<a@b.com>", "a b@c.com", "")) {
			Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("email", bad));
			body.put("proof", Map.of("challenge", "x", "signature", "0x00"));
			assertThat(laptop.post("/api/users/alert-email", body).code()).isEqualTo("VALIDATION_FAILED");
		}
		// The device label comes from the User-Agent header; a line break in it must not reach the mail.
		assertThat(AlertEmails.plain("Chrome\r\nBcc: x@evil.test")).doesNotContain("\n").doesNotContain("\r");
	}

	@Test
	void aMailServerThatIsDownNeverFailsASignIn() {
		withVerifiedAddress("owner@example.com");
		doThrow(new MailSendException("connection refused")).when(mail).send(any(SimpleMailMessage.class));

		Api.Response login = login(browser(), wallet);

		assertThat(login.status()).isEqualTo(200);
		await().atMost(Duration.ofSeconds(5)).until(() -> count(
				"select count(*) from authentication_events where event_type = 'ALERT_EMAIL_FAILED'") == 1);
	}

	@Test
	void alertsAreLimitedPerHourAndChangingTheAddressTellsTheOldOne() {
		Api laptop = withVerifiedAddress("first@example.com");
		// Three new devices; the limit is two alert mails an hour (the limiter is not cleared: it holds that count).
		for (int i = 0; i < 3; i++) {
			assertThat(login(browser(), wallet).status()).isEqualTo(200);
		}
		await().atMost(Duration.ofSeconds(5)).until(() -> count(
				"select count(*) from authentication_events where event_type = 'ALERT_EMAIL_LIMITED'") >= 1);

		reset(mail);
		rateLimiter.clear();
		assertThat(setAddress(laptop, "second@example.com").status()).isEqualTo(200);
		// Alerts for the sign-ins above may still arrive after the reset: wait for the two expected messages.
		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			verify(mail, atLeastOnce()).send(captor.capture());
			assertThat(captor.getAllValues()).anySatisfy(m -> {
				assertThat(m.getTo()).containsExactly("first@example.com");
				assertThat(m.getSubject()).isEqualTo(AlertMailer.CHANGED_SUBJECT);
			});
			assertThat(captor.getAllValues()).anySatisfy(m -> {
				assertThat(m.getTo()).containsExactly("second@example.com");
				assertThat(m.getSubject()).isEqualTo(AlertMailer.VERIFY_SUBJECT);
			});
		});
	}

	@Test
	void removingNeedsAFreshSignature() {
		Api laptop = withVerifiedAddress("owner@example.com");
		assertThat(laptop.post("/api/users/alert-email/remove", Map.of("proof", Map.of("challenge", "x",
				"signature", "0x" + "00".repeat(65)))).code()).isEqualTo("STEP_UP_FAILED");

		assertThat(laptop.post("/api/users/alert-email/remove", proof(laptop, wallet, "REMOVE_ALERT_EMAIL", Map.of()))
			.status()).isEqualTo(204);
		assertThat(laptop.get("/api/users/alert-email").body().get("address").isNull()).isTrue();
	}

}
