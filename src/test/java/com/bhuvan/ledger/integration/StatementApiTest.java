package com.bhuvan.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhuvan.ledger.AbstractIntegrationTest;
import com.bhuvan.ledger.entity.AccountStatus;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.service.AccountService;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.TransactionFacade;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class StatementApiTest extends AbstractIntegrationTest {

    private static final String SYSTEM_ACCOUNT = "SYS-CASH-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionFacade facade;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void pagesThrough120EntriesNewestFirstWithNoDuplicatesOrGaps() throws Exception {
        String acc = newAccount();
        for (int i = 1; i <= 120; i++) {
            depositDirect(acc, i * 100L); // 1.00, 2.00, ... 120.00
        }

        List<Long> seen = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        Long beforeId = null;
        do {
            String page = entries(acc, "?limit=50" + (beforeId == null ? "" : "&beforeId=" + beforeId));
            List<Number> ids = JsonPath.read(page, "$.entries[*].entryId");
            pageSizes.add(ids.size());
            ids.forEach(id -> seen.add(id.longValue()));
            Number next = JsonPath.read(page, "$.nextBeforeId");
            beforeId = next == null ? null : next.longValue();
        } while (beforeId != null);

        assertThat(pageSizes).containsExactly(50, 50, 20);
        assertThat(new HashSet<>(seen)).hasSize(120); // no duplicates
        assertThat(seen).isSortedAccordingTo((x, y) -> Long.compare(y, x)); // newest first
        assertThat(seen).isEqualTo(jdbc.queryForList("""
                select e.id from ledger_entries e join accounts a on a.id = e.account_id
                 where a.account_number = ? order by e.id desc
                """, Long.class, acc)); // no gaps: exactly the account's entries
    }

    @Test
    void entryFieldsDescribeEachMovement() throws Exception {
        String acc = newAccount();
        String other = newAccount();
        depositDirect(acc, 10_000);
        String transfer = mockMvc.perform(post("/api/v1/transactions/transfer")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccount\":\"" + acc + "\",\"toAccount\":\"" + other
                                + "\",\"amount\":\"25.50\",\"description\":\"rent\"}"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/v1/accounts/" + acc + "/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(acc))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].transactionId").value((String) JsonPath.read(transfer,
                        "$.transactionId")))
                .andExpect(jsonPath("$.entries[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value("25.50"))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value("74.50"))
                .andExpect(jsonPath("$.entries[0].description").value("rent"))
                .andExpect(jsonPath("$.entries[0].createdAt").exists())
                .andExpect(jsonPath("$.entries[1].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value("100.00"))
                .andExpect(jsonPath("$.nextBeforeId").isEmpty());
        mockMvc.perform(get("/api/v1/accounts/" + other + "/entries"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value("25.50"));
    }

    @Test
    void defaultLimitIs20() throws Exception {
        String acc = newAccount();
        for (int i = 0; i < 25; i++) {
            depositDirect(acc, 100);
        }
        String page = entries(acc, "");
        assertThat((List<?>) JsonPath.read(page, "$.entries")).hasSize(20);
        assertThat((Object) JsonPath.read(page, "$.nextBeforeId")).isNotNull();
    }

    @Test
    void exactlyLimitEntriesIsOnePageWithNoNextCursor() throws Exception {
        String acc = newAccount();
        for (int i = 0; i < 5; i++) {
            depositDirect(acc, 100);
        }
        String page = entries(acc, "?limit=5");
        assertThat((List<?>) JsonPath.read(page, "$.entries")).hasSize(5);
        assertThat((Object) JsonPath.read(page, "$.nextBeforeId")).isNull();
    }

    @Test
    void accountWithNoEntriesGivesAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + newAccount() + "/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.nextBeforeId").isEmpty());
    }

    @Test
    void newEntriesArrivingBetweenPagesDontShiftTheCursor() throws Exception {
        String acc = newAccount();
        for (int i = 0; i < 6; i++) {
            depositDirect(acc, 100);
        }
        String first = entries(acc, "?limit=3");
        List<Number> firstIds = JsonPath.read(first, "$.entries[*].entryId");
        Number next = JsonPath.read(first, "$.nextBeforeId");

        depositDirect(acc, 100); // lands on top, not in the middle of the next page
        depositDirect(acc, 100);

        String second = entries(acc, "?limit=3&beforeId=" + next);
        List<Number> secondIds = JsonPath.read(second, "$.entries[*].entryId");
        assertThat(secondIds).hasSize(3);
        assertThat(secondIds.get(0).longValue()).isLessThan(firstIds.get(2).longValue());
        assertThat((Object) JsonPath.read(second, "$.nextBeforeId")).isNull();
    }

    @Test
    void closedAccountStatementIsStillReadable() throws Exception {
        String acc = newAccount();
        depositDirect(acc, 500);
        facade.execute(PostingCommand.of(TransactionType.WITHDRAWAL, key(), acc, SYSTEM_ACCOUNT, 500, "INR",
                null, null));
        accountService.changeStatus(acc, AccountStatus.CLOSED);

        mockMvc.perform(get("/api/v1/accounts/" + acc + "/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value("0.00"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/ACC9999999999/entries"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.accountNumber").value("ACC9999999999"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=0", "?limit=101", "?limit=-5", "?limit=abc", "?beforeId=0", "?beforeId=-1",
            "?beforeId=x"})
    void invalidPagingParametersAre400(String query) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + SYSTEM_ACCOUNT + "/entries" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void limitOf100IsAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + SYSTEM_ACCOUNT + "/entries?limit=100"))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private String entries(String acc, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/accounts/" + acc + "/entries" + query))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void depositDirect(String acc, long paise) {
        assertThat(facade.execute(PostingCommand.of(TransactionType.DEPOSIT, key(), SYSTEM_ACCOUNT, acc, paise,
                "INR", null, null)).httpStatus()).isEqualTo(201);
    }

    private String newAccount() {
        return accountService.create("Statement Test").accountNumber();
    }

    private static String key() {
        return "st-" + UUID.randomUUID();
    }
}
