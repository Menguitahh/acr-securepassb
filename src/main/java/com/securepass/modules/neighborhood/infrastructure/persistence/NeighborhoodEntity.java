package com.securepass.modules.neighborhood.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * securepassb#258: fila de {@code neighborhoods} para el panel de la
 * plataforma. Solo lectura: el alta sigue en NeighborhoodProvisioner, que
 * crea barrio, clave, garita y admin en una transaccion.
 */
@Entity
@Table(name = "neighborhoods")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class NeighborhoodEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 60)
    private String slug;

    @Column(nullable = false, length = 60)
    private String timezone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
