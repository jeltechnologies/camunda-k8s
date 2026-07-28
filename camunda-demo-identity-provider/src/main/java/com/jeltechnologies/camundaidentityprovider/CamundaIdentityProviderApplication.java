package com.jeltechnologies.camundaidp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CamundaIdpApplication {

    public static void main(String[] args) {
        SpringApplication.run(CamundaIdpApplication.class, args);
    }
}
