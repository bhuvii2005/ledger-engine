package com.bhuvan.ledger.service;

import com.bhuvan.ledger.dto.AccountResponse;
import com.bhuvan.ledger.dto.StatementResponse;
import com.bhuvan.ledger.entity.Account;
import com.bhuvan.ledger.entity.AccountStatus;
import com.bhuvan.ledger.entity.AccountType;
import com.bhuvan.ledger.entity.LedgerEntry;
import com.bhuvan.ledger.entity.LedgerTransaction;
import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import com.bhuvan.ledger.repository.AccountRepository;
import com.bhuvan.ledger.repository.LedgerEntryRepository;
import com.bhuvan.ledger.repository.LedgerTransactionRepository;
import com.bhuvan.ledger.util.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerTransactionRepository transactionRepository;

    @Transactional
    public AccountResponse create(String ownerName) {
        // numbers come from a Postgres sequence, so two concurrent creates can never collide
        String accountNumber = "ACC" + String.format("%010d", accountRepository.nextAccountNumber());
        Account account = accountRepository.save(Account.builder()
                .accountNumber(accountNumber)
                .ownerName(ownerName)
                .accountType(AccountType.USER)
                .build());
        log.info("Account created accountNumber={}", accountNumber);
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .map(AccountResponse::from)
                .orElseThrow(() -> LedgerException.accountNotFound(accountNumber));
    }

    /**
     * Keyset pagination on entry id, newest first. Unlike OFFSET, a page boundary doesn't
     * shift when new entries arrive, so paging through never repeats or skips an entry.
     */
    @Transactional(readOnly = true)
    public StatementResponse statement(String accountNumber, int limit, Long beforeId) {
        Long accountId = accountRepository.findIdByAccountNumber(accountNumber)
                .orElseThrow(() -> LedgerException.accountNotFound(accountNumber));

        // one extra row tells us whether there is an older page, without a count query
        List<LedgerEntry> rows = entryRepository.findPage(accountId,
                beforeId == null ? Long.MAX_VALUE : beforeId, PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        List<LedgerEntry> page = more ? rows.subList(0, limit) : rows;

        Map<UUID, LedgerTransaction> transactions = transactionRepository
                .findAllById(page.stream().map(LedgerEntry::getTransactionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(LedgerTransaction::getId, Function.identity()));

        List<StatementResponse.Entry> entries = page.stream().map(e -> {
            LedgerTransaction txn = transactions.get(e.getTransactionId());
            return new StatementResponse.Entry(e.getId(), e.getTransactionId(), txn.getTxnType().name(),
                    e.getDirection().name(), Money.format(e.getAmount()), Money.format(e.getBalanceAfter()),
                    txn.getDescription(), e.getCreatedAt());
        }).toList();
        return new StatementResponse(accountNumber, entries, more ? page.get(page.size() - 1).getId() : null);
    }

    @Transactional
    public AccountResponse changeStatus(String accountNumber, AccountStatus target) {
        // id first, then lock: the same order postings use, so a status change can't
        // race with a posting that already checked the account was ACTIVE
        Long id = accountRepository.findIdByAccountNumber(accountNumber)
                .orElseThrow(() -> LedgerException.accountNotFound(accountNumber));
        Account account = accountRepository.lockById(id)
                .orElseThrow(() -> LedgerException.accountNotFound(accountNumber));

        validateTransition(account, target);

        if (account.getStatus() != target) {
            AccountStatus previous = account.getStatus();
            account.setStatus(target);
            accountRepository.flush(); // runs @PreUpdate now, so the response has the new updatedAt
            log.info("Account status changed accountNumber={} from={} to={}", accountNumber, previous, target);
        }
        return AccountResponse.from(account);
    }

    private static void validateTransition(Account account, AccountStatus target) {
        String number = account.getAccountNumber();
        if (account.getAccountType() == AccountType.SYSTEM) {
            throw new LedgerException(ErrorCode.SYSTEM_ACCOUNT_IMMUTABLE,
                    "System account " + number + " cannot change status").with("accountNumber", number);
        }
        AccountStatus current = account.getStatus();
        if (current == AccountStatus.CLOSED) {
            throw new LedgerException(ErrorCode.ACCOUNT_CLOSED,
                    "Account " + number + " is closed and cannot be changed").with("accountNumber", number);
        }
        if (target == AccountStatus.CLOSED) {
            if (current != AccountStatus.ACTIVE) {
                throw new LedgerException(ErrorCode.ACCOUNT_NOT_ACTIVE,
                        "Only an ACTIVE account can be closed; unfreeze it first").with("accountNumber", number);
            }
            if (account.getBalance() != 0) {
                throw new LedgerException(ErrorCode.ACCOUNT_HAS_BALANCE,
                        "Account " + number + " must have a zero balance to close").with("accountNumber", number);
            }
        }
    }
}
