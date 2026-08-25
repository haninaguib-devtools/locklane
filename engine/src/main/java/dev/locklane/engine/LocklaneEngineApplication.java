package dev.locklane.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling drives GhIssueCache's background refresh (#4).
@SpringBootApplication
@EnableScheduling
public class LocklaneEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocklaneEngineApplication.class, args);
    }
}
