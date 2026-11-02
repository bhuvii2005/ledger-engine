package com.bhuvan.ledger.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhuvan.ledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class SwaggerUiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiDocumentListsTheAccountEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Ledger Engine API"))
                .andExpect(jsonPath("$.paths['/api/v1/accounts'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/{accountNumber}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/{accountNumber}/status'].patch").exists());
    }

    @Test
    void openApiDocumentListsTransactionEndpointsWithTheIdempotencyHeader() throws Exception {
        for (String op : new String[] {"deposit", "withdraw", "transfer"}) {
            String path = "$.paths['/api/v1/transactions/" + op + "'].post";
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(jsonPath(path + ".parameters[?(@.name == 'Idempotency-Key')].in").value("header"))
                    .andExpect(jsonPath(path + ".parameters[?(@.name == 'Idempotency-Key')].required").value(true))
                    .andExpect(jsonPath(path + ".responses['201'].content['application/json'].schema.$ref")
                            .value("#/components/schemas/TransactionResponse"));
        }
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/transactions/{transactionId}'].get").exists());
    }

    @Test
    void swaggerUiPageLoads() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui")));
    }
}
