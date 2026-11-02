package com.bhuvan.ledger.service;

import com.bhuvan.ledger.dto.TransactionResponse;
import com.bhuvan.ledger.entity.Account;
import com.bhuvan.ledger.entity.AccountStatus;
import com.bhuvan.ledger.entity.AccountType;
import com.bhuvan.ledger.entity.Direction;
import com.bhuvan.ledger.entity.IdempotencyRecord;
import com.bhuvan.ledger.entity.IdempotencyStatus;
import com.bhuvan.ledger.entity.LedgerEntry;
import com.bhuvan.ledger.entity.LedgerTransaction;
import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import com.bhuvan.ledger.exception.Problems;
import com.bhuvan.ledger.repository.AccountRepository;
import com.bhuvan.ledger.repository.IdempotencyRecordRepository;
import com.bhuvan.ledger.util.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts one money movement inside a single DB transaction: claim the idempotency
 * key, lock both accounts in id order, validate, write the transaction and both
 * entries, update balances, record the response. Retries live in TransactionFacade,
 * outside this transaction.
 */
@Slf4j
@Service
public class PostingService {

    private final AccountRepository accountRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final long maxAmountPaise;

    public PostingService(AccountRepository accountRepository,
            IdempotencyRecordRepository idempotencyRepository,
            EntityManager entityManager,
            ObjectMapper objectMapper,
            @Value("${ledger.max-transaction-amount-paise}") long maxAmountPaise) {
        this.accountRepository = accountRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.maxAmountPaise = maxAmountPaise;
    }

    @Transactional
    public PostingResult post(PostingCommand cmd) {
        boolean claimed = idempotencyRepository.claim(
                cmd.idempotencyKey(), cmd.requestHash(), cmd.operation().name()) == 1;
        IdempotencyRecord record = idempotencyRepository.findById(cmd.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency row missing right after claim"));
        if (!claimed) {
            return replay(record, cmd);
        }

        try {
            TransactionResponse response = execute(cmd);
            String body = toJson(response);
            complete(record, 201, body, response.transactionId());
            log.info("Posted {} txnId={} amountPaise={} from={} to={}", cmd.operation(),
                    response.transactionId(), cmd.amountPaise(), cmd.sourceAccountNumber(), cmd.destAccountNumber());
            return new PostingResult(201, body, response.transactionId(), false);
        } catch (LedgerException rejection) {
            // A business "no" is a final answer for this key: store it and commit, so a retry
            // with the same key gets the same 404/422 instead of a different outcome later.
            // Nothing else has been written at this point; every check runs before the inserts.
            int status = rejection.getCode().status().value();
            String body = toJson(Problems.of(rejection));
            complete(record, status, body, null);
            log.info("Rejected {} key={} code={}", cmd.operation(), cmd.idempotencyKey(), rejection.getCode());
            return new PostingResult(status, body, null, false);
        }
    }

    private PostingResult replay(IdempotencyRecord record, PostingCommand cmd) {
        if (!record.getRequestHash().equals(cmd.requestHash())) {
            // not stored: the key keeps its original meaning
            throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key " + cmd.idempotencyKey() + " was already used for a different request");
        }
        if (record.getStatus() != IdempotencyStatus.COMPLETED) {
            // With the row inserted and completed in one DB transaction, other sessions never see
            // a committed IN_PROGRESS row. Kept as a guard in case that ever stops being true.
            throw new LedgerException(ErrorCode.REQUEST_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed");
        }
        return new PostingResult(record.getHttpStatus(), record.getResponseBody(), record.getTransactionId(), true);
    }

    private TransactionResponse execute(PostingCommand cmd) {
        if (!"INR".equals(cmd.currency())) {
            throw new LedgerException(ErrorCode.CURRENCY_NOT_SUPPORTED,
                    "Currency " + cmd.currency() + " is not supported; only INR is")
                    .with("currency", cmd.currency());
        }

        // ids only, no entities: nothing unlocked (and possibly stale) enters the persistence context
        Long sourceId = resolveId(cmd.sourceAccountNumber());
        Long destId = resolveId(cmd.destAccountNumber());
        if (sourceId.equals(destId)) {
            throw sameAccount(cmd);
        }

        // Always lock the smaller id first. A->B and B->A then queue on the same row
        // instead of each holding one lock and waiting for the other (deadlock).
        Account first = lock(Math.min(sourceId, destId));
        Account second = lock(Math.max(sourceId, destId));
        Account source = sourceId < destId ? first : second;
        Account dest = sourceId < destId ? second : first;

        validate(cmd, source, dest);

        long sourceAfter = Money.subtract(source.getBalance(), cmd.amountPaise());
        long destAfter = Money.add(dest.getBalance(), cmd.amountPaise());

        LedgerTransaction txn = LedgerTransaction.builder()
                .id(UUID.randomUUID())
                .txnType(cmd.operation())
                .amount(cmd.amountPaise())
                .currency(cmd.currency())
                .description(cmd.description())
                .idempotencyKey(cmd.idempotencyKey())
                .reversesTxnId(cmd.reversesTxnId())
                .build();
        // persist, not repository.save: the id is assigned, so save() would merge and SELECT first
        entityManager.persist(txn);
        entityManager.persist(LedgerEntry.builder().transactionId(txn.getId()).accountId(source.getId())
                .direction(Direction.DEBIT).amount(cmd.amountPaise()).balanceAfter(sourceAfter).build());
        entityManager.persist(LedgerEntry.builder().transactionId(txn.getId()).accountId(dest.getId())
                .direction(Direction.CREDIT).amount(cmd.amountPaise()).balanceAfter(destAfter).build());

        // managed and locked; dirty checking writes these on flush
        source.setBalance(sourceAfter);
        dest.setBalance(destAfter);

        return new TransactionResponse(txn.getId(), cmd.operation().name(), Money.format(cmd.amountPaise()),
                cmd.currency(), source.getAccountNumber(), dest.getAccountNumber(),
                Money.format(sourceAfter), Money.format(destAfter), cmd.reversesTxnId(), txn.getCreatedAt());
    }

    // Runs after both locks are held, so status and balance can't change underneath us.
    private void validate(PostingCommand cmd, Account source, Account dest) {
        if (requestNamesSource(cmd.operation())) {
            requireUserAccount(source);
        }
        if (requestNamesDest(cmd.operation())) {
            requireUserAccount(dest);
        }
        requireActive(source);
        requireActive(dest);
        if (source.getAccountType() == AccountType.USER && source.getBalance() < cmd.amountPaise()) {
            throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Account " + source.getAccountNumber() + " has insufficient funds")
                    .with("accountNumber", source.getAccountNumber());
        }
        if (cmd.amountPaise() > maxAmountPaise) {
            throw new LedgerException(ErrorCode.AMOUNT_LIMIT_EXCEEDED,
                    "Amount exceeds the per-transaction limit of " + Money.format(maxAmountPaise))
                    .with("limit", Money.format(maxAmountPaise));
        }
    }

    private LedgerException sameAccount(PostingCommand cmd) {
        // For a deposit or withdrawal the other side is the system account, so source == dest
        // means the client named the system account itself.
        if (cmd.operation() == TransactionType.DEPOSIT || cmd.operation() == TransactionType.WITHDRAWAL) {
            String named = cmd.operation() == TransactionType.DEPOSIT
                    ? cmd.destAccountNumber() : cmd.sourceAccountNumber();
            return systemAccountImmutable(named);
        }
        return new LedgerException(ErrorCode.SAME_ACCOUNT_TRANSFER,
                "Source and destination are the same account").with("accountNumber", cmd.sourceAccountNumber());
    }

    private static boolean requestNamesSource(TransactionType type) {
        return type == TransactionType.WITHDRAWAL || type == TransactionType.TRANSFER;
    }

    private static boolean requestNamesDest(TransactionType type) {
        return type == TransactionType.DEPOSIT || type == TransactionType.TRANSFER;
    }

    private static void requireUserAccount(Account account) {
        if (account.getAccountType() == AccountType.SYSTEM) {
            throw systemAccountImmutable(account.getAccountNumber());
        }
    }

    private static LedgerException systemAccountImmutable(String accountNumber) {
        return new LedgerException(ErrorCode.SYSTEM_ACCOUNT_IMMUTABLE,
                "System account " + accountNumber + " cannot be used through this endpoint")
                .with("accountNumber", accountNumber);
    }

    private static void requireActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "Account " + account.getAccountNumber() + " is " + account.getStatus())
                    .with("accountNumber", account.getAccountNumber())
                    .with("status", account.getStatus().name());
        }
    }

    private Long resolveId(String accountNumber) {
        return accountRepository.findIdByAccountNumber(accountNumber)
                .orElseThrow(() -> LedgerException.accountNotFound(accountNumber));
    }

    private Account lock(Long id) {
        // the id came from this transaction a moment ago and accounts are never deleted
        return accountRepository.lockById(id)
                .orElseThrow(() -> new IllegalStateException("Account id " + id + " vanished"));
    }

    private void complete(IdempotencyRecord record, int httpStatus, String body, UUID transactionId) {
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setHttpStatus(httpStatus);
        record.setResponseBody(body);
        record.setTransactionId(transactionId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize response", e);
        }
    }
}
