package com.github.lechandonga.markettickpipelinejava;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 允许解析 NaN / Infinity 这类非标准 JSON 数字 token，
 * 使它们能进入取值校验并被区分为 INVALID_PRICE / INVALID_QUANTITY，
 * 而不是笼统地报 MALFORMED_JSON。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer nonNumericNumbersCustomizer() {
        return builder -> builder.featuresToEnable(
                JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature());
    }
}
