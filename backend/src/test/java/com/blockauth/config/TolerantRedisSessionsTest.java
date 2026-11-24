package com.blockauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

class TolerantRedisSessionsTest {

	@SuppressWarnings("unchecked")
	private final SessionRepository<MapSession> redis = mock(SessionRepository.class);
	private final TolerantRedisSessions.Tolerant<MapSession> sessions = new TolerantRedisSessions.Tolerant<>(redis);

	@Test
	void anInvalidatedSessionIsNotWrittenBackAndTheRequestDoesNotFail() {
		MapSession session = new MapSession();
		doThrow(new IllegalStateException("Session was invalidated")).when(redis).save(session);

		assertThatCode(() -> sessions.save(session)).doesNotThrowAnyException();
	}

	@Test
	void aHalfWrittenSessionIsDeletedAndTreatedAsAbsent() {
		when(redis.findById("s1")).thenThrow(new IllegalStateException("creationTime key must not be null"));

		assertThat(sessions.findById("s1")).isNull();
		verify(redis).deleteById("s1");
	}

}
