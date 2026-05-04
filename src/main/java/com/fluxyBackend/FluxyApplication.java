package com.fluxyBackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FluxyApplication {

	public static void main(String[] args) {
		SpringApplication.run(FluxyApplication.class, args);
	}

}
