package com.zipick;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class ZipickApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZipickApplication.class, args);
    }
}
