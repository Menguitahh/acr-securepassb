package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QrTokenCodecTest {

    private final QrTokenCodec codec = new QrTokenCodec(new ObjectMapper());

    @Test
    void encodeParseRoundTrip() {
        QrTokenPayload payload = new QrTokenPayload(
                1, "abcd1234", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "S", 1755523200L, 1755566400L, 1, null, null, null);
        byte[] fakeSig = new byte[64];

        String token = codec.encode(payload, fakeSig);

        Optional<ParsedQrToken> parsed = codec.parse(token);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().payload()).isEqualTo(payload);
        assertThat(parsed.get().signature()).isEqualTo(fakeSig);
    }

    @Test
    void parseSinPrefijoDevuelveEmpty() {
        assertThat(codec.parse("no-es-un-token")).isEmpty();
    }

    @Test
    void parseConPartesFaltantesDevuelveEmpty() {
        assertThat(codec.parse("SP1.solounaparte")).isEmpty();
        assertThat(codec.parse("SP1..")).isEmpty();
    }

    @Test
    void parseNullDevuelveEmpty() {
        assertThat(codec.parse(null)).isEmpty();
    }
}
