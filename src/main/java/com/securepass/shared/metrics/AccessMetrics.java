package com.securepass.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Contadores custom sobre accesos. La rate por minuto y la latencia p95
 * salen de las metricas HTTP por defecto (`http.server.requests`). Este
 * bean solo agrega el corte por resultado, que Micrometer no expone solo.
 */
@AllArgsConstructor
@Component
public class AccessMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public void recordResult(String result, String source) {
        counters.computeIfAbsent(result + "|" + source, k -> Counter.builder("securepass.access.results")
                .description("Conteo de resultados de acceso por fuente")
                .tag("result", result)
                .tag("source", source)
                .register(registry))
                .increment();
    }
}
