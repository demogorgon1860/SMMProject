package com.smmpanel.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for Instagram bot geo-routing.
 *
 * <p>Architecture: - Panel publishes orders to instagram.direct exchange with routing key (KR/DE) -
 * Korean bot consumes from instagram.orders.kr queue - German bot consumes from instagram.orders.de
 * queue - Both bots publish results to instagram.results queue - Failed orders go to instagram.dead
 * via dead letter exchange
 */
@Configuration
public class RabbitMQConfig {

    // Exchange names
    public static final String INSTAGRAM_EXCHANGE = "instagram.direct";
    public static final String INSTAGRAM_DLX = "instagram.dlx";

    // Queue names
    public static final String QUEUE_ORDERS_KR = "instagram.orders.kr";
    public static final String QUEUE_ORDERS_DE = "instagram.orders.de";
    public static final String QUEUE_RESULTS = "instagram.results";
    public static final String QUEUE_DEAD = "instagram.dead";

    // Routing keys
    public static final String ROUTING_KEY_KR = "KR";
    public static final String ROUTING_KEY_DE = "DE";

    // ==================== Exchanges ====================

    @Bean
    public DirectExchange instagramExchange() {
        return ExchangeBuilder.directExchange(INSTAGRAM_EXCHANGE).durable(true).build();
    }

    @Bean
    public FanoutExchange instagramDeadLetterExchange() {
        return ExchangeBuilder.fanoutExchange(INSTAGRAM_DLX).durable(true).build();
    }

    // ==================== Queues ====================

    @Bean
    public Queue instagramOrdersKR() {
        return QueueBuilder.durable(QUEUE_ORDERS_KR)
                .withArgument("x-dead-letter-exchange", INSTAGRAM_DLX)
                .withArgument("x-message-ttl", 86400000) // 24 hours TTL
                .build();
    }

    @Bean
    public Queue instagramOrdersDE() {
        return QueueBuilder.durable(QUEUE_ORDERS_DE)
                .withArgument("x-dead-letter-exchange", INSTAGRAM_DLX)
                .withArgument("x-message-ttl", 86400000) // 24 hours TTL
                .build();
    }

    @Bean
    public Queue instagramResults() {
        return QueueBuilder.durable(QUEUE_RESULTS).build();
    }

    @Bean
    public Queue instagramDeadLetter() {
        return QueueBuilder.durable(QUEUE_DEAD)
                .withArgument("x-message-ttl", 604800000) // 7 days TTL
                .build();
    }

    // ==================== Bindings ====================

    @Bean
    public Binding bindingKR(Queue instagramOrdersKR, DirectExchange instagramExchange) {
        return BindingBuilder.bind(instagramOrdersKR).to(instagramExchange).with(ROUTING_KEY_KR);
    }

    @Bean
    public Binding bindingDE(Queue instagramOrdersDE, DirectExchange instagramExchange) {
        return BindingBuilder.bind(instagramOrdersDE).to(instagramExchange).with(ROUTING_KEY_DE);
    }

    @Bean
    public Binding bindingDeadLetter(
            Queue instagramDeadLetter, FanoutExchange instagramDeadLetterExchange) {
        return BindingBuilder.bind(instagramDeadLetter).to(instagramDeadLetterExchange);
    }

    // ==================== Message Converter ====================

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
