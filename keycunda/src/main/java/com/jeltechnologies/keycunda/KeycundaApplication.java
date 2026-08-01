package com.jeltechnologies.keycunda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KeycundaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeycundaApplication.class, args);
    }
}
