package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Kafka Order Processor Application.
 * 
 * MongoDB repositories are NOT auto-scanned at startup.
 * They are conditionally enabled via MongoConfig when app.mongodb.enabled=true.
 */
@SpringBootApplication
@EnableMongoRepositories(basePackages = "none") // Disable default scanning, MongoConfig handles it
public class KafkaOrderProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaOrderProcessorApplication.class, args);
    }
}
