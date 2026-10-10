package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * securepassb#257: fila de {@code lots} mapeada con JPA. Es un detalle de
 * persistencia: el dominio sigue siendo el record {@link Lot}, con sus
 * validaciones, y los casos de uso no ven esta clase. Flyway es duenio del
 * esquema; Hibernate solo lo valida (ddl-auto: validate).
 */
@Entity
@Table(name = "lots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA lo necesita
@AllArgsConstructor
class LotEntity {

    @Id
    private UUID id;

    @Column(name = "neighborhood_id", nullable = false, updatable = false)
    private UUID neighborhoodId;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @Column(length = 120)
    private String street;

    @Column(length = 20)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LotStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    static LotEntity from(Lot lot) {
        return new LotEntity(lot.id(), lot.neighborhoodId(), lot.code(), lot.street(), lot.number(),
                lot.status(), lot.createdAt(), lot.updatedAt());
    }

    Lot toDomain() {
        return new Lot(id, neighborhoodId, code, street, number, status, createdAt, updatedAt);
    }
}
