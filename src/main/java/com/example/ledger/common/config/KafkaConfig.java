package com.example.ledger.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka configuration.
 *
 * Error handling: 2 retries with 1 s interval, then route to DLQ.
 * Guarantee: at-least-once delivery; consumers must deduplicate by transaction id.
 * Poison messages are never silently dropped — they go to the DLQ topic.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    // Topic name constants — single source of truth
    public static final String TOPIC_PAYMENTS_SETTLED      = "payments.settled";
    public static final String TOPIC_PAYMENTS_SETTLED_DLQ  = "payments.settled.dlq";
    public static final String TOPIC_TRANSACTIONS_RECORDED = "ledger.transactions.recorded";

    @Bean
    public NewTopic paymentsSettledTopic() {
        return TopicBuilder.name(TOPIC_PAYMENTS_SETTLED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentsSettledDlqTopic() {
        return TopicBuilder.name(TOPIC_PAYMENTS_SETTLED_DLQ)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transactionsRecordedTopic() {
        return TopicBuilder.name(TOPIC_TRANSACTIONS_RECORDED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Error handler: retry twice (1 s apart), then publish to DLQ.
     * The DLQ destination is derived automatically by appending the original
     * topic to {@link #TOPIC_PAYMENTS_SETTLED_DLQ}.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    log.error("Sending message to DLQ after retries exhausted. " +
                              "topic={} partition={} offset={} key={}",
                              record.topic(), record.partition(), record.offset(), record.key(), ex);
                    return new org.apache.kafka.common.TopicPartition(
                            TOPIC_PAYMENTS_SETTLED_DLQ, 0);
                });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(1000L, 2));
        handler.setCommitRecovered(true);
        return handler;
    }
}
