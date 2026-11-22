package com.elepay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The single ELEPAY application. The legacy class name is retained for build and test compatibility. */
@SpringBootApplication
@EnableScheduling
public class SandboxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxApplication.class, args);
    }
}
