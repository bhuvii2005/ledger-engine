package com.bhuvan.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Ledger Engine API")
                .version("v1")
                .description("Double-entry ledger: accounts, deposits, withdrawals, transfers and reversals. "
                        + "Amounts are strings in rupees with up to two decimals, e.g. \"1000.50\"."));
    }
}
