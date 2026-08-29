package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Minor;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.AgeOverMaximumException;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisterMinorUseCaseTest {

    private MinorRepository minors;
    private RegisterMinorUseCase useCase;
    private CurrentUser actor;
    private UUID neighborhoodId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        minors = mock(MinorRepository.class);
        useCase = new RegisterMinorUseCase(minors);
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        actor = new CurrentUser(UUID.randomUUID(), neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));
        when(minors.findLotNeighborhood(lotId)).thenReturn(Optional.of(neighborhoodId));
    }

    private RegisterMinorUseCase.Command cmd(String dni, LocalDate birth) {
        return new RegisterMinorUseCase.Command("Juan Menor", dni, birth, "Hijo", null);
    }

    private LocalDate ageYears(int years) {
        return LocalDate.now().minusYears(years).minusDays(1);
    }

    @Test
    void altaConDatosValidosPersisteYDevuelveMinor() {
        Minor result = useCase.register(actor, lotId, cmd("55111222", ageYears(10)));

        assertThat(result.fullName()).isEqualTo("Juan Menor");
        assertThat(result.documentNumber()).isEqualTo("55111222");
        assertThat(result.neighborhoodId()).isEqualTo(neighborhoodId);
        assertThat(result.createdBy()).isEqualTo(actor.userId());
        ArgumentCaptor<Minor> cap = ArgumentCaptor.forClass(Minor.class);
        verify(minors).save(cap.capture());
        assertThat(cap.getValue().id()).isEqualTo(result.id());
    }

    @Test
    void dniOpcional_altaSinDniPersiste() {
        Minor result = useCase.register(actor, lotId, cmd(null, ageYears(4)));
        assertThat(result.documentNumber()).isNull();
        verify(minors).save(any(Minor.class));
    }

    @Test
    void mayorODeEdadTope_lanzaAgeOverMaximum() {
        assertThatThrownBy(() -> useCase.register(actor, lotId, cmd("55111222", ageYears(16))))
                .isInstanceOf(AgeOverMaximumException.class)
                .satisfies(ex -> {
                    AgeOverMaximumException e = (AgeOverMaximumException) ex;
                    assertThat(e.maxAge()).isEqualTo(16);
                    assertThat(e.lotId()).isEqualTo(lotId);
                });
        verify(minors, never()).save(any());
    }

    @Test
    void loteAjenoAlBarrio_devuelve403() {
        UUID otherBarrio = UUID.randomUUID();
        when(minors.findLotNeighborhood(lotId)).thenReturn(Optional.of(otherBarrio));

        assertThatThrownBy(() -> useCase.register(actor, lotId, cmd("55111222", ageYears(10))))
                .isInstanceOf(AccessDeniedException.class);
        verify(minors, never()).save(any());
    }

    @Test
    void loteInexistente_devuelve404() {
        when(minors.findLotNeighborhood(lotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.register(actor, lotId, cmd("55111222", ageYears(10))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void dniDuplicadoEnElLote_devuelveBusinessRuleViolation() {
        doThrow(new DuplicateKeyException("uq_lot_minors_lot_document"))
                .when(minors).save(any(Minor.class));

        assertThatThrownBy(() -> useCase.register(actor, lotId, cmd("55111222", ageYears(10))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("ya hay un menor con ese DNI");
    }
}
