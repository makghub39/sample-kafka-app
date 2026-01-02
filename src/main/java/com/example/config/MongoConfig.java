package com.example.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB Configuration.
 * Only enables MongoDB repositories when app.mongodb.enabled=true.
 * This prevents the application from failing when MongoDB is not available.
 */
@Configuration
@ConditionalOnProperty(name = "app.mongodb.enabled", havingValue = "true")
@EnableMongoRepositories(basePackages = "com.example.repository")
public class MongoConfig {
    // MongoDB auto-configuration will handle the rest when this is enabled
}
