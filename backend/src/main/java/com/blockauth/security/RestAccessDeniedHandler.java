package com.blockauth.security;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import com.blockauth.apikey.ApiKeyPrincipal;
import com.blockauth.audit.AttackType;
import com.blockauth.audit.AuditService;
import com.blockauth.common.ApiErrorWriter;
import com.blockauth.common.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 403 as JSON, recording either a CSRF rejection or a privilege-escalation attempt. */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private static final Set<String> STANDARD_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE",
			"OPTIONS", "TRACE");

	private final AuditService audit;
	private final ApiErrorWriter errors;

	public RestAccessDeniedHandler(AuditService audit, ApiErrorWriter errors) {
		this.audit = audit;
		this.errors = errors;
	}

	@Override
	public void handle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull AccessDeniedException exception)
			throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Object caller = authentication == null ? null : authentication.getPrincipal();
		BlockAuthPrincipal principal = caller instanceof BlockAuthPrincipal p ? p : null;
		ApiKeyPrincipal key = caller instanceof ApiKeyPrincipal k ? k : null;
		String wallet = principal == null ? null : principal.walletAddress();
		UUID userId = principal != null ? principal.userId() : key != null ? key.ownerId() : null;
		String ip = ClientInfoResolver.resolve(request).ip();

		if (exception instanceof CsrfException) {
			audit.recordSecurityEvent(AttackType.CSRF_REJECTED, wallet, userId, ip);
			errors.write(request, response, ErrorCode.CSRF_INVALID);
			return;
		}
		// A key used outside its scopes is recorded against its owner, naming the key by its id (never its secret).
		audit.recordSecurityEvent(AttackType.PRIVILEGE_ESCALATION_ATTEMPT,
				"Target: " + method(request.getMethod()) + " " + protectedArea(RateLimitFilter.routedPath(request)) + "."
						+ (key == null ? "" : " API key " + key.keyId() + "."),
				wallet, userId, ip);
		errors.write(request, response, ErrorCode.ACCESS_DENIED);
	}

	/** The method as one of the standard names: the request's own text must not reach the stored description. */
	private static String method(String method) {
		return STANDARD_METHODS.contains(method) ? method : "another method";
	}

	/**
	 * Names the protected area that was requested. The stored description must be server-generated
	 * text, so the area is one of three fixed labels rather than the caller's path.
	 */
	private static String protectedArea(String path) {
		if (path.startsWith("/api/admin/")) {
			return "/api/admin";
		}
		if (path.startsWith("/api/blockchain/")) {
			return "/api/blockchain";
		}
		return "an unmapped path";
	}

}
