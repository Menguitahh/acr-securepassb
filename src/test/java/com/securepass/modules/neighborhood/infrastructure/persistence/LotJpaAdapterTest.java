package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.neighborhood.application.LotRepository;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** securepassb#257: el puerto LotRepository sobre JPA se comporta como el JDBC de antes. */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LotJpaAdapterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired LotRepository lots;
    @Autowired JdbcTemplate jdbc;
    UUID barrio;
    Instant now;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)", barrio, "b-" + barrio);
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Lot lot(String code, LotStatus status) {
        Lot lot = new Lot(UUID.randomUUID(), barrio, code, "Calle", "1", status, now, now);
        lots.save(lot);
        return lot;
    }

    @Test
    void guardaYLeeIgualQueElDominio() {
        Lot saved = lot("A-1", LotStatus.ACTIVE);
        assertThat(lots.findById(saved.id())).contains(saved);
        assertThat(lots.findByNeighborhoodAndCode(barrio, "A-1")).contains(saved);
    }

    @Test
    void codigoRepetidoEnElBarrioFallaAlGuardar() {
        lot("A-1", LotStatus.ACTIVE);
        assertThatThrownBy(() -> lot("A-1", LotStatus.ACTIVE)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void autocompletePorPrefijoSoloActivosYSinComodines() {
        lot("A-14", LotStatus.ACTIVE);
        lot("a-15", LotStatus.ACTIVE);
        lot("A-16", LotStatus.SUSPENDED);
        lot("A_9", LotStatus.ACTIVE);

        assertThat(lots.searchActiveByCodePrefix(barrio, "a-1", 10)).extracting(Lot::code)
                .containsExactly("A-14", "a-15");
        // "_" no es comodin: no matchea "A-14".
        assertThat(lots.searchActiveByCodePrefix(barrio, "A_", 10)).extracting(Lot::code).containsExactly("A_9");
        assertThat(lots.searchActiveByCodePrefix(barrio, "A", 1)).hasSize(1);
    }

    @Test
    void paginaPorCodigoYActualizaEstado() {
        lot("B-2", LotStatus.ACTIVE);
        Lot first = lot("B-1", LotStatus.ACTIVE);
        assertThat(lots.listByNeighborhood(barrio, 0, 1)).extracting(Lot::code).containsExactly("B-1");
        assertThat(lots.listByNeighborhood(barrio, 1, 1)).extracting(Lot::code).containsExactly("B-2");

        Instant later = now.plusSeconds(60);
        lots.update(first.withStatus(LotStatus.SUSPENDED, later));
        Lot read = lots.findById(first.id()).orElseThrow();
        assertThat(read.status()).isEqualTo(LotStatus.SUSPENDED);
        assertThat(read.updatedAt()).isEqualTo(later);
        assertThat(read.createdAt()).isEqualTo(now);
    }
}
