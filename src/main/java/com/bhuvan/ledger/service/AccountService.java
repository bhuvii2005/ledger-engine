package com.bhuvan.ledger.service;

import com.bhuvan.ledger.dto.AccountResponse;
import com.bhuvan.ledger.entity.Account;
import com.bhuvan.ledger.entity.AccountStatus;
import com.bhuvan.ledger.entity.AccountType;
import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import com.bhuvan.ledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

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
