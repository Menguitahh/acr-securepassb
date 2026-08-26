package com.securepass.shared.ratelimit;

import java.time.Duration;
import java.util.Optional;

public interface RateLimiter {

    /**
     * Verifica y consume una unidad de la ventana. Devuelve `empty()` si el
     * evento se acepta; devuelve el `Duration` sugerido de `Retry-After` si
     * el limite se supero.
     */
    Optional<Duration> tryAcquire(String key, int limit, Duration window);
}
