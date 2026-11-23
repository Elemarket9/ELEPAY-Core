package com.elepay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The sole production entry point; development and simulated services stay on the test classpath. */
@SpringBootApplication
@EnableScheduling
public class ElepayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ElepayApplication.class, args);
    }
}
