package com.example.ledger.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * Jackson 3.x (tools.jackson) configuration for Spring Boot 4.
 *
 * Jackson 3.x has Java time support built into jackson-databind; no JavaTimeModule needed.
 * Key settings:
 *  - USE_BIG_DECIMAL_FOR_FLOATS: amounts are never parsed as double/float — BigDecimal only
 *  - WRITE_DATES_AS_TIMESTAMPS: disabled so dates are serialized as ISO-8601 strings
 *  - FAIL_ON_UNKNOWN_PROPERTIES: disabled (defaults to false in Jackson 3.x, explicit here)
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer ledgerJacksonCustomizer() {
        return builder -> {
            // Never parse floating-point numbers as double — always use BigDecimal
            builder.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            // Write dates as ISO-8601 strings, not numeric timestamps
            builder.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
            // Do not fail on unknown JSON properties (forward-compatibility)
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        };
    }
}
