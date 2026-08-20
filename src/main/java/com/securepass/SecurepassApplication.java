package com.securepass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SecurepassApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecurepassApplication.class, args);
    }
}
