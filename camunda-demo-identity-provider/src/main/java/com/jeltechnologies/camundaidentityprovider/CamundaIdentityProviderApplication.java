package com.jeltechnologies.camundaidentityprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CamundaIdentityProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(CamundaIdentityProviderApplication.class, args);
    }
}
