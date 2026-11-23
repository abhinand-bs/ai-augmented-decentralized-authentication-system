package com.blockauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.blockauth.apikey.ApiKeyPrincipal;
import com.blockauth.audit.AttackType;
import com.blockauth.audit.AuditService;
import com.blockauth.common.ApiErrorWriter;

import jakarta.servlet.FilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * What a privilege-escalation or CSRF event records: only server-generated text, and who the caller
 * was, whether a signed-in user or an API key.
 */
class SecurityEventContextTest {

	private static final String WALLET = "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed";

	private final ApiErrorWriter errors = new ApiErrorWriter(new ObjectMapper());
	private final AuditService audit = mock(AuditService.class);
	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void aCustomMethodNameNeverReachesTheStoredDescription() throws Exception {
		UUID userId = signedIn();
		MockHttpServletRequest request = routed("X".repeat(2000), "/api/admin/users");

		new RestAccessDeniedHandler(audit, errors).handle(request, response, new AccessDeniedException("denied"));

		ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
		verify(audit).recordSecurityEvent(eq(AttackType.PRIVILEGE_ESCALATION_ATTEMPT), description.capture(),
				eq(WALLET), eq(userId), eq("127.0.0.1"));
		assertThat(description.getValue()).isEqualTo("Target: another method /api/admin.");
		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	void aKeyUsedOutsideItsScopesIsRecordedAgainstItsOwner() throws Exception {
		UUID keyId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				new ApiKeyPrincipal(keyId, "monitoring", ownerId), null,
				List.of(new SimpleGrantedAuthority("SCOPE_users:read"))));

		new RestAccessDeniedHandler(audit, errors).handle(routed("GET", "/api/admin/statistics"), response,
				new AccessDeniedException("denied"));

		verify(audit).recordSecurityEvent(eq(AttackType.PRIVILEGE_ESCALATION_ATTEMPT),
				eq("Target: GET /api/admin. API key " + keyId + "."), isNull(), eq(ownerId), eq("127.0.0.1"));
	}

	@Test
	void aForeignOriginIsRecordedAgainstTheSignedInCaller() throws Exception {
		UUID userId = signedIn();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
		request.addHeader("Origin", "http://evil.test");
		FilterChain chain = mock(FilterChain.class);

		new OriginCheckFilter("http://localhost:5173", audit, errors).doFilter(request, response, chain);

		verify(audit).recordSecurityEvent(AttackType.CSRF_REJECTED, WALLET, userId, "127.0.0.1");
		assertThat(response.getStatus()).isEqualTo(403);
		verifyNoInteractions(chain);
	}

	/** A request as the servlet container hands it on, with the path it routes by. */
	private static MockHttpServletRequest routed(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setServletPath(path);
		return request;
	}

	private UUID signedIn() {
		UUID userId = UUID.randomUUID();
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				new BlockAuthPrincipal(userId, WALLET, UUID.randomUUID()), null,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))));
		return userId;
	}

}
