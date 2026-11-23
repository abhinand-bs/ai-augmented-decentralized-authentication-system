package com.blockauth.security;

import java.io.IOException;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.blockauth.common.ApiErrorWriter;
import com.blockauth.common.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * With shared sessions, an unreachable Redis means no session can be read or saved. This filter, placed in front
 * of Spring Session's own, turns that into a clean 503 instead of a 500 with a stack trace: authenticated
 * requests and sign-in fail closed. The health endpoint does not use a session and stays as it is.
 */
@Configuration
@ConditionalOnProperty(name = "blockauth.redis.enabled", havingValue = "true")
public class SessionStoreFailureFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(SessionStoreFailureFilter.class);
	// Spring Session's filter runs at Integer.MIN_VALUE + 50; this one wraps it.
	private static final int ORDER = Integer.MIN_VALUE + 40;

	private final ApiErrorWriter errors;

	public SessionStoreFailureFilter(ApiErrorWriter errors) {
		this.errors = errors;
	}

	@Bean
	FilterRegistrationBean<SessionStoreFailureFilter> sessionStoreFailureRegistration() {
		FilterRegistrationBean<SessionStoreFailureFilter> registration = new FilterRegistrationBean<>(this);
		registration.setOrder(ORDER);
		return registration;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		}
		catch (DataAccessException e) {
			log.error("Session store unavailable: {}", e.getClass().getSimpleName());
			if (!response.isCommitted()) {
				response.reset();
				errors.write(request, response, ErrorCode.DATABASE_UNAVAILABLE);
			}
		}
	}

}
