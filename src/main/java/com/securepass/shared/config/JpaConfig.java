package com.securepass.shared.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * securepassb#257: JPA convive con JdbcClient.
 *
 * El transaction manager principal sigue siendo el de JDBC: el alta de
 * usuarios usa savepoints (transacciones anidadas) y el de JPA no los
 * soporta. Los repositorios JPA usan su propio manager.
 *
 * Sin transacciones por defecto en los repositorios: una lectura JPA dentro
 * de una transaccion JDBC (el alta lee el lote) no abre otra sobre la misma
 * conexion. Las escrituras JPA declaran @Transactional(JPA_TX) en el
 * adaptador.
 *
 * ponytail: una escritura JPA dentro de una transaccion JDBC no comparte esa
 * transaccion. Hoy no pasa (lotes se crean y editan solos); si un caso de uso
 * mezcla las dos, pasar ese modulo entero a JPA.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.securepass", transactionManagerRef = JpaConfig.JPA_TX,
        enableDefaultTransactions = false)
public class JpaConfig {

    public static final String JPA_TX = "jpaTransactionManager";

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean(JPA_TX)
    public PlatformTransactionManager jpaTransactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
