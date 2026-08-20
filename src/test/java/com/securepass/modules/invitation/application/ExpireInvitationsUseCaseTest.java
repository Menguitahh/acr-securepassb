package com.securepass.modules.invitation.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpireInvitationsUseCaseTest {

    @Test
    void materializaExpiredConCutoffAhora() {
        InvitationRepository repo = mock(InvitationRepository.class);
        when(repo.expireOverdue(any(Instant.class))).thenReturn(3);

        int rows = new ExpireInvitationsUseCase(repo).run();

        assertThat(rows).isEqualTo(3);
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repo).expireOverdue(captor.capture());
        assertThat(captor.getValue()).isBeforeOrEqualTo(Instant.now());
    }

    private static <T> T any(Class<T> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }
}
