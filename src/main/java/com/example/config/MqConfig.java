package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;

/**
 * IBM MQ Configuration.
 * Only enabled when app.wmq.enabled=true.
 * 
 * Uses Spring Boot auto-configuration from mq-jms-spring-boot-starter 4.0.1.
 * The connection factory is automatically created when the starter is present.
 * 
 * Configuration properties in application-docker.yml:
 *   ibm.mq.queue-manager: QM1
 *   ibm.mq.channel: DEV.APP.SVRCONN
 *   ibm.mq.conn-name: localhost(1414)
 *   ibm.mq.user: app
 *   ibm.mq.password: passw0rd
 */
@Configuration
@ConditionalOnProperty(name = "app.wmq.enabled", havingValue = "true")
public class MqConfig {

    @Value("${app.wmq.queue-name:DEV.QUEUE.1}")
    private String queueName;

    /**
     * Creates a JmsTemplate for sending messages to IBM MQ.
     * The ConnectionFactory is auto-configured by mq-jms-spring-boot-starter.
     */
    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setDefaultDestinationName(queueName);
        return template;
    }
}
