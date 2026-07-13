package com.fintech.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: habilita o sweep periódico do LoginRateLimiter (#144).
@EnableScheduling
@SpringBootApplication
public class FintechApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(FintechApiApplication.class, args);
	}

}
