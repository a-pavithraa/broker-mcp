package com.broker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BrokerMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokerMcpApplication.class, args);
    }

}
