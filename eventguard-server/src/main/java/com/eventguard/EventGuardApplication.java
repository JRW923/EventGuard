package com.eventguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EventGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventGuardApplication.class, args);
    }
}
