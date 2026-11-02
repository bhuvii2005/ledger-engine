package com.bhuvan.ledger.controller;

import com.bhuvan.ledger.dto.CashRequest;
import com.bhuvan.ledger.dto.TransactionDetailsResponse;
import com.bhuvan.ledger.dto.TransactionResponse;
import com.bhuvan.ledger.dto.TransferRequest;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import com.bhuvan.ledger.service.PostingCommand;
import com.bhuvan.ledger.service.PostingResult;
import com.bhuvan.ledger.service.TransactionFacade;
import com.bhuvan.ledger.service.TransactionQueryService;
import com.bhuvan.ledger.util.Money;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final TransactionFacade facade;
    private final TransactionQueryService queryService;
    private final String systemAccountNumber;

    public TransactionController(TransactionFacade facade, TransactionQueryService queryService,
            @Value("${ledger.system-account-number}") String systemAccountNumber) {
        this.facade = facade;
        this.queryService = queryService;
        this.systemAccountNumber = systemAccountNumber;
    }

    @PostMapping(path = "/deposit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @CreatedTransaction
    public ResponseEntity<String> deposit(
            @IdempotencyKeyHeader @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CashRequest request) {
        return post(PostingCommand.of(TransactionType.DEPOSIT, requireKey(key),
                systemAccountNumber, request.accountNumber(), Money.parse(request.amount()),
                "INR", request.description(), null));
    }

    @PostMapping(path = "/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    @CreatedTransaction
    public ResponseEntity<String> withdraw(
            @IdempotencyKeyHeader @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CashRequest request) {
        return post(PostingCommand.of(TransactionType.WITHDRAWAL, requireKey(key),
                request.accountNumber(), systemAccountNumber, Money.parse(request.amount()),
                "INR", request.description(), null));
    }

    @PostMapping(path = "/transfer", consumes = MediaType.APPLICATION_JSON_VALUE)
    @CreatedTransaction
    public ResponseEntity<String> transfer(
            @IdempotencyKeyHeader @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody TransferRequest request) {
        return post(PostingCommand.of(TransactionType.TRANSFER, requireKey(key),
                request.fromAccount(), request.toAccount(), Money.parse(request.amount()),
                request.currency(), request.description(), null));
    }

    @GetMapping("/{transactionId}")
    public TransactionDetailsResponse get(@PathVariable UUID transactionId) {
        return queryService.get(transactionId);
    }

    private ResponseEntity<String> post(PostingCommand cmd) {
        PostingResult result = facade.execute(cmd);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.httpStatus())
                .contentType(result.isSuccess() ? MediaType.APPLICATION_JSON : PROBLEM_JSON);
        if (result.transactionId() != null) {
            response.location(URI.create("/api/v1/transactions/" + result.transactionId()));
        }
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new LedgerException(ErrorCode.MISSING_IDEMPOTENCY_KEY,
                    "The " + IDEMPOTENCY_KEY + " header is required for this request");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new LedgerException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    IDEMPOTENCY_KEY + " must be 8-64 characters of letters, digits, '-' or '_'");
        }
        return key;
    }

    // OpenAPI only: Spring sees the header as optional so a missing one reaches our own 400.
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    @Parameter(in = ParameterIn.HEADER, name = IDEMPOTENCY_KEY, required = true,
            description = "Unique per logical request, 8-64 chars of [A-Za-z0-9_-]. Resending the same key "
                    + "with the same body replays the original response.")
    @interface IdempotencyKeyHeader {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "201", description = "Posted, or replayed if Idempotent-Replayed: true",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TransactionResponse.class)))
    @interface CreatedTransaction {
    }
}
