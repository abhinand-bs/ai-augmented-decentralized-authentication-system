package com.blockauth.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * With sessions in Redis, a request still running when another request (a logout) deletes the session can write
 * part of it back: Spring Session then fails that request ("Session was invalidated") or a later one ("creationTime
 * key must not be null"), both as 500. Found in the live test of 2026-11-23. This wrapper makes both cases mean
 * "no session": an invalidated session is never written back, and a half-written one is deleted and ignored, so
 * the browser simply continues signed out.
 */
@Configuration
@ConditionalOnProperty(name = "blockauth.redis.enabled", havingValue = "true")
public class TolerantRedisSessions {

	private static final Logger log = LoggerFactory.getLogger(TolerantRedisSessions.class);

	@Bean
	static BeanPostProcessor tolerantSessionRepository() {
		return new BeanPostProcessor() {
			@Override
			@SuppressWarnings({ "unchecked", "rawtypes" })
			public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String name) {
				return bean instanceof SessionRepository<?> repository && !(bean instanceof Tolerant)
						? new Tolerant(repository) : bean;
			}
		};
	}

	static final class Tolerant<S extends Session> implements SessionRepository<S> {

		private final SessionRepository<S> delegate;

		Tolerant(SessionRepository<S> delegate) {
			this.delegate = delegate;
		}

		@Override
		public S createSession() {
			return delegate.createSession();
		}

		@Override
		public void save(S session) {
			try {
				delegate.save(session);
			}
			catch (IllegalStateException e) {
				// Deleted meanwhile by a logout or a new sign-in: it must stay deleted.
				log.debug("Session not saved: it was invalidated by a concurrent request");
			}
		}

		@Override
		public S findById(String id) {
			try {
				return delegate.findById(id);
			}
			catch (IllegalStateException e) {
				log.info("Discarded an incomplete session left by a concurrent logout");
				delegate.deleteById(id);
				return null;
			}
		}

		@Override
		public void deleteById(String id) {
			delegate.deleteById(id);
		}

	}

}
