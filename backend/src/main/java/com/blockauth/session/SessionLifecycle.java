package com.blockauth.session;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

/**
 * Keeps session records in step with the servlet sessions. Without Redis, an idle session that times out closes
 * its record here, and records left open by a previous run are closed at startup (servlet sessions live in this
 * process's memory, so none survive a restart). With Redis, sessions outlive a restart of any one instance, so
 * nothing is closed at startup, and expired sessions are closed by {@link SessionReaper} instead (Redis does not
 * reliably report expiries to the application).
 */
@Component
public class SessionLifecycle implements HttpSessionListener {

	private static final Logger log = LoggerFactory.getLogger(SessionLifecycle.class);

	private final UserSessionRepository sessions;
	private final Clock clock;
	private final boolean sharedSessions;

	public SessionLifecycle(UserSessionRepository sessions, Clock clock,
			@Value("${blockauth.redis.enabled:false}") boolean sharedSessions) {
		this.sessions = sessions;
		this.clock = clock;
		this.sharedSessions = sharedSessions;
	}

	@Override
	public void sessionDestroyed(HttpSessionEvent event) {
		// Logout and sign-in remove the attribute before invalidating, so this sees only time-outs.
		if (event.getSession().getAttribute(SessionRegistry.SESSION_ID_ATTRIBUTE) instanceof UUID id) {
			try {
				sessions.end(id, SessionEndReason.EXPIRED, clock.instant());
			}
			catch (RuntimeException e) {
				log.warn("Could not close the record of an expired session: {}", e.getClass().getSimpleName());
			}
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void closeRecordsOfThePreviousRun() {
		if (sharedSessions) {
			return;
		}
		int closed = sessions.endAllOpen(SessionEndReason.RESTART, clock.instant());
		if (closed > 0) {
			log.info("Closed {} session records left open by the previous run", closed);
		}
	}

}
