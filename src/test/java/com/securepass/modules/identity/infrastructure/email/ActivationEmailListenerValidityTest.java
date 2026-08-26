package com.securepass.modules.identity.infrastructure.email;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * validityLabel es un helper privado, pero el bug que arreglo (piso vs
 * techo del Duration) es del tipo que un unit test barato ataja. Lo
 * ejercitamos por reflexion para no cambiar la visibilidad solo por test.
 */
class ActivationEmailListenerValidityTest {

    @Test
    void redondeaHaciaArribaParaNoImprimirUnHoraCuandoElTtlEs2Horas() throws Exception {
        Instant now = Instant.parse("2026-08-24T21:00:00Z");
        // TTL de 2 horas menos 1 segundo, tipico gap entre issue y listener
        Instant expires = now.plusSeconds(7199);
        assertThat(invoke(now, expires)).isEqualTo("2 horas");
    }

    @Test
    void redondeaHaciaArribaParaNoImprimirUnDiaCuandoElTtlEs7Dias() throws Exception {
        Instant now = Instant.parse("2026-08-24T21:00:00Z");
        Instant expires = now.plusSeconds(7L * 86400 - 1);
        assertThat(invoke(now, expires)).isEqualTo("7 días");
    }

    @Test
    void unaHoraExactaDiceUnHora() throws Exception {
        Instant now = Instant.parse("2026-08-24T21:00:00Z");
        assertThat(invoke(now, now.plusSeconds(3600))).isEqualTo("1 hora");
    }

    @Test
    void unDiaExactoDiceUnDia() throws Exception {
        Instant now = Instant.parse("2026-08-24T21:00:00Z");
        assertThat(invoke(now, now.plusSeconds(86400))).isEqualTo("1 día");
    }

    private String invoke(Instant from, Instant to) throws Exception {
        Method m = ActivationEmailListener.class.getDeclaredMethod("validityLabel", Instant.class, Instant.class);
        m.setAccessible(true);
        return (String) m.invoke(null, from, to);
    }
}
