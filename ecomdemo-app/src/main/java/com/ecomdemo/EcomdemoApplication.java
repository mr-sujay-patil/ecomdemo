package com.ecomdemo;

import com.ecomdemo.outbox.EnableOutbox;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The order service (still named ecomdemo-app).
 *
 * <p>{@code @EnableOutbox} since Phase 24. This application's package already contains the
 * library's, so it would find the outbox without it - but so would nothing else, and the
 * annotation is what says, on the class that starts the service, that it takes part in the saga.
 */
@SpringBootApplication
@EnableOutbox
public class EcomdemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(EcomdemoApplication.class, args);
	}

}
