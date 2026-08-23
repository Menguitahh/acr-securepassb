package com.securepass.modules.notification.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * WBS-43: push AFTER_COMMIT no puede correr en el hilo del request. El
 * objetivo de p95 de `/access/validate` es < 200 ms; una llamada HTTP a
 * Expo puede colgarse hasta el timeout. Un push que se pierde es una
 * molestia; un escaneo que tarda 5 s rompe el producto.
 *
 * Virtual threads (Java 21) porque la carga es I/O-bound y espordica:
 * un thread por task sin pool, sin ceremonia.
 */
@Configuration
@EnableAsync
public class NotificationAsyncConfig {

    @Bean("pushExecutor")
    public Executor pushExecutor() {
        return new ConcurrentTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean("expoRestClient")
    @ConditionalOnProperty(name = "securepass.push.provider", havingValue = "expo")
    public RestClient expoRestClient(
            @Value("${securepass.push.expo.access-token:}") String accessToken) {
        return ExpoPushSender.defaultHttpClient(accessToken);
    }
}
