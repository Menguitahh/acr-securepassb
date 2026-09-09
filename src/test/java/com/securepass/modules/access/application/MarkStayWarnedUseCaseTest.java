package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.Stay;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarkStayWarnedUseCaseTest {

    private static final Instant ENTER = Instant.parse("2026-09-01T15:00:00Z");

    private StayRepository stays;
    private MarkStayWarnedUseCase useCase;

    private UUID neighborhoodId;
    private UUID stayId;

    @BeforeEach
    void setUp() {
        stays = mock(StayRepository.class);
        useCase = new MarkStayWarnedUseCase(stays);
        neighborhoodId = UUID.randomUUID();
        stayId = UUID.randomUUID();
    }

    private Stay openStay() {
        return new Stay(
                stayId, neighborhoodId, UUID.randomUUID(), UUID.randomUUID(),
                "Maria Lopez", "30123456", 0,
                UUID.randomUUID(), null, ENTER, null,
                ENTER.plusSeconds(3600), false, null);
    }

    @Test
    void marcaWarnedAtYDevuelveElValor() {
        Instant now = Instant.now();
        when(stays.findById(stayId)).thenReturn(Optional.of(openStay()));
        when(stays.markWarned(eq(stayId), any(Instant.class))).thenReturn(now);

        MarkStayWarnedUseCase.Result r = useCase.mark(neighborhoodId, stayId);

        assertThat(r.stayId()).isEqualTo(stayId);
        assertThat(r.warnedAt()).isEqualTo(now);
    }

    @Test
    void idempotenteDevuelveElWarnedAtOriginal() {
        Instant original = ENTER.plusSeconds(1200);
        when(stays.findById(stayId)).thenReturn(Optional.of(openStay()));
        when(stays.markWarned(eq(stayId), any(Instant.class))).thenReturn(original);

        MarkStayWarnedUseCase.Result r = useCase.mark(neighborhoodId, stayId);

        assertThat(r.warnedAt()).isEqualTo(original);
    }

    @Test
    void stayInexistenteEs404() {
        when(stays.findById(stayId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.mark(neighborhoodId, stayId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(stays, never()).markWarned(any(), any());
    }

    @Test
    void stayDeOtroBarrioEs404() {
        Stay foreign = new Stay(
                stayId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Maria Lopez", "30123456", 0,
                UUID.randomUUID(), null, ENTER, null,
                ENTER.plusSeconds(3600), false, null);
        when(stays.findById(stayId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> useCase.mark(neighborhoodId, stayId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(stays, never()).markWarned(any(), any());
    }

    @Test
    void stayCerradaEs409() {
        Stay closed = openStay().close(UUID.randomUUID(), ENTER.plusSeconds(60), false, null);
        when(stays.findById(stayId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> useCase.mark(neighborhoodId, stayId))
                .isInstanceOf(StateConflictException.class);
        verify(stays, never()).markWarned(any(), any());
    }
}
