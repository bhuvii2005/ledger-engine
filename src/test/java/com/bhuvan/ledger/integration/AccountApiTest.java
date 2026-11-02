package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.service.AccountService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class AccountApiTest extends AbstractIntegrationTest {

    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountService accountService;

    // ---------- create ----------

    @Test
    void createAccountReturns201WithZeroBalanceAndGeneratedNumber() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Ravi Kumar\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/accounts/ACC")))
                .andExpect(jsonPath("$.accountNumber").value(org.hamcrest.Matchers.matchesPattern("ACC\\d{10}")))
                .andExpect(jsonPath("$.ownerName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.type").value("USER"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void createAccountTrimsOwnerName() throws Exception {
        createAccount("   Asha Rao   ").andExpect(jsonPath("$.ownerName").value("Asha Rao"));
    }

    @Test
    void createAccountAcceptsOwnerNameOfExactly100Characters() throws Exception {
        createAccount("a".repeat(100)).andExpect(status().isCreated());
    }

    @Test
    void createAccountRejectsOwnerNameOver100Characters() throws Exception {
        createAccount("a".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("ownerName")));
    }

    @Test
    void createAccountRejectsBlankOwnerName() throws Exception {
        createAccount("   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createAccountRejectsMissingOwnerName() throws Exception {
        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createAccountRejectsControlCharactersInOwnerName() throws Exception {
        postJson("{\"ownerName\":\"Ravi\\u0000Kumar\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createAccountRejectsJsonNumberWhereStringExpected() throws Exception {
        postJson("{\"ownerName\":12345}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Field 'ownerName' must be a JSON string"));
    }

    @Test
    void createAccountRejectsUnknownField() throws Exception {
        postJson("{\"ownerName\":\"Ravi\",\"balance\":\"5000.00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Unknown field 'balance'"));
    }

    @Test
    void malformedJsonIs400WithoutLeakingInternals() throws Exception {
        postJson("{\"ownerName\": \"Ravi\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("jackson"))))
                .andExpect(content().string(not(containsString("at com."))));
    }

    @Test
    void emptyBodyIs400() throws Exception {
        postJson("")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.TEXT_PLAIN).content("ownerName=Ravi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @Timeout(30)
    void concurrentCreatesGetDistinctAccountNumbers() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return accountService.create("Concurrent Owner").accountNumber();
                }));
            }
            start.countDown();
            Set<String> numbers = new HashSet<>();
            for (Future<String> f : futures) {
                numbers.add(f.get());
            }
            assertThat(numbers).hasSize(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- get ----------

    @Test
    void getAccountReturnsIt() throws Exception {
        String number = newAccount();
        mockMvc.perform(get("/api/v1/accounts/" + number))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(number))
                .andExpect(jsonPath("$.balance").value("0.00"));
    }

    @Test
    void timestampsFromCreateMatchWhatALaterReadReturns() throws Exception {
        String created = createAccount("Clock Check").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String number = JsonPath.read(created, "$.accountNumber");
        String fetched = mockMvc.perform(get("/api/v1/accounts/" + number))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(fetched, "$.createdAt"))
                .isEqualTo(JsonPath.read(created, "$.createdAt"));
        assertThat((String) JsonPath.read(fetched, "$.updatedAt"))
                .isEqualTo(JsonPath.read(created, "$.updatedAt"));
    }

    @Test
    void getUnknownAccountIs404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/ACC9999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.accountNumber").value("ACC9999999999"));
    }

    @Test
    void systemAccountIsReadableWithNegativeOrZeroBalanceFormatted() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + SYSTEM_ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SYSTEM"))
                .andExpect(jsonPath("$.balance").value(org.hamcrest.Matchers.matchesPattern("-?\\d+\\.\\d{2}")));
    }

    // ---------- status ----------

    @Test
    void freezeThenUnfreeze() throws Exception {
        String number = newAccount();
        changeStatus(number, "FROZEN").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FROZEN"));
        changeStatus(number, "ACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void settingTheSameStatusIsANoOp() throws Exception {
        String number = newAccount();
        changeStatus(number, "ACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void closeAccountWithZeroBalance() throws Exception {
        String number = newAccount();
        changeStatus(number, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void closeAccountWithBalanceIsRejected() throws Exception {
        String number = newAccount();
        moneyMove("deposit", number, "500.00").andExpect(status().isCreated());

        changeStatus(number, "CLOSED")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_BALANCE"))
                .andExpect(jsonPath("$.accountNumber").value(number));
        mockMvc.perform(get("/api/v1/accounts/" + number))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value("500.00"));
    }

    @Test
    void accountCanBeClosedOnceItsBalanceIsWithdrawnToZero() throws Exception {
        String number = newAccount();
        moneyMove("deposit", number, "75.25").andExpect(status().isCreated());
        moneyMove("withdraw", number, "75.25").andExpect(status().isCreated());
        changeStatus(number, "CLOSED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void closingAFrozenAccountIsRejected() throws Exception {
        String number = newAccount();
        changeStatus(number, "FROZEN").andExpect(status().isOk());
        changeStatus(number, "CLOSED")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void anyStatusChangeOnAClosedAccountIsRejected() throws Exception {
        String number = newAccount();
        changeStatus(number, "CLOSED").andExpect(status().isOk());
        for (String target : List.of("ACTIVE", "FROZEN", "CLOSED")) {
            changeStatus(number, target)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        }
    }

    @Test
    void systemAccountStatusCannotBeChanged() throws Exception {
        for (String target : List.of("FROZEN", "CLOSED", "ACTIVE")) {
            changeStatus(SYSTEM_ACCOUNT, target)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_IMMUTABLE"));
        }
    }

    @Test
    void statusChangeOnUnknownAccountIs404() throws Exception {
        changeStatus("ACC9999999999", "FROZEN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void invalidStatusValueIs400() throws Exception {
        String number = newAccount();
        changeStatus(number, "DELETED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Field 'status' has an invalid value"));
    }

    @Test
    void missingStatusIs400() throws Exception {
        String number = newAccount();
        mockMvc.perform(patch("/api/v1/accounts/" + number + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @Timeout(30)
    void statusChangeWaitsForTheRowLockAndReturns503WhenItTimesOut() throws Exception {
        String number = newAccount();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "select id from accounts where account_number = ? for update")) {
                ps.setString(1, number);
                ps.executeQuery();
            }

            long started = System.nanoTime();
            changeStatus(number, "FROZEN")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("SYSTEM_BUSY"));
            long waitedMs = (System.nanoTime() - started) / 1_000_000;
            // proves the request actually blocked on the lock until lock_timeout (2s) fired
            assertThat(waitedMs).isGreaterThanOrEqualTo(1_800);

            holder.rollback();
        }
        mockMvc.perform(get("/api/v1/accounts/" + number)).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- framework errors still carry a code ----------

    @Test
    void unknownRouteIs404WithCode() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethodIs405WithCode() throws Exception {
        mockMvc.perform(delete("/api/v1/accounts/ACC1000000001"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // ---------- helpers ----------

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions createAccount(String ownerName) throws Exception {
        return postJson("{\"ownerName\":\"" + ownerName + "\"}");
    }

    private String newAccount() throws Exception {
        String body = createAccount("Test Owner").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accountNumber");
    }

    private ResultActions changeStatus(String accountNumber, String target) throws Exception {
        return mockMvc.perform(patch("/api/v1/accounts/" + accountNumber + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + target + "\"}"));
    }

    private ResultActions moneyMove(String operation, String accountNumber, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/" + operation)
                .header("Idempotency-Key", "acct-test-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountNumber\":\"" + accountNumber + "\",\"amount\":\"" + amount + "\"}"));
    }
}
