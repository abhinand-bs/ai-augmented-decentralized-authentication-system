package com.blockauth.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import com.blockauth.audit.AuditService;
import com.blockauth.audit.ClientInfo;
import com.blockauth.auth.StepUpProof;
import com.blockauth.auth.StepUpService;
import com.blockauth.blockchain.WalletRegistryClient;
import com.blockauth.security.BlockAuthPrincipal;
import com.blockauth.session.UserSessionRepository;

class EmergencyStopServiceTest {

	private static final ClientInfo CLIENT = new ClientInfo("127.0.0.1", "JUnit");
	private static final StepUpProof PROOF = new StepUpProof("challenge", "0x" + "00".repeat(65));

	private final BlockAuthPrincipal admin = new BlockAuthPrincipal(UUID.randomUUID(),
			"0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", UUID.randomUUID());
	private final EmergencyStopRepository stops = mock(EmergencyStopRepository.class);
	private final WalletRegistryClient registry = mock(WalletRegistryClient.class);
	private final EmergencyStopService service = new EmergencyStopService(stops, mock(UserSessionRepository.class),
			mock(StepUpService.class), mock(AuditService.class), registry,
			Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC), mock(PlatformTransactionManager.class));

	@Test
	void theContractIsPausedOnlyAfterTheStopIsRecorded() {
		when(stops.findById(EmergencyStop.ID)).thenReturn(Optional.empty());
		when(stops.lockCurrent()).thenReturn(Optional.of(EmergencyStop.inactive()));

		service.activate(admin, "Incident review", true, false, PROOF, CLIENT);

		InOrder order = inOrder(stops, registry);
		order.verify(stops).save(any());
		order.verify(registry).pause();
	}

	@Test
	void aStopThatCouldNotBeSavedLeavesTheContractUnpaused() {
		when(stops.findById(EmergencyStop.ID)).thenReturn(Optional.empty());
		when(stops.lockCurrent()).thenThrow(new DataAccessResourceFailureException("database down"));

		assertThatThrownBy(() -> service.activate(admin, "Incident review", true, false, PROOF, CLIENT))
			.isInstanceOf(DataAccessResourceFailureException.class);
		verify(registry, never()).pause();
	}

}
