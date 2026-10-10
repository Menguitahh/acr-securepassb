package com.securepass.shared.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache en memoria (ConcurrentMap, el default de Spring) para lecturas que se
 * repiten en cada request y no cambian:
 *
 *   neighborhoodNames  nombre del barrio (mails, pedidos de revision). Ningun
 *                      endpoint renombra un barrio.
 *   signingKeysByKid   clave de firma por kid, consultada en cada escaneo de
 *                      QR. La clave publica de un kid no cambia; rotar una
 *                      clave vacia el cache (@CacheEvict en markInactive).
 *
 * ponytail: cache por instancia y sin vencimiento; pasar a Redis (ya esta en
 * el stack) si el backend corre en mas de una instancia.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String NEIGHBORHOOD_NAMES = "neighborhoodNames";
    public static final String SIGNING_KEYS_BY_KID = "signingKeysByKid";

    // Explicito: con Redis en el classpath Spring elegiria RedisCacheManager, y
    // las claves de firma no son serializables. Solo estos dos caches existen.
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(NEIGHBORHOOD_NAMES, SIGNING_KEYS_BY_KID);
    }
}
