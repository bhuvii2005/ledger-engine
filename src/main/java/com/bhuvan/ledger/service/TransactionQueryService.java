package com.bhuvan.ledger.service;

import com.bhuvan.ledger.dto.TransactionDetailsResponse;
import com.bhuvan.ledger.entity.Account;
import com.bhuvan.ledger.entity.Direction;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public TransactionDetailsResponse get(UUID transactionId) {
        LedgerTransaction txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "No transaction with id " + transactionId).with("transactionId", transactionId));
        List<LedgerEntry> entries = entryRepository.findByTransactionIdOrderByIdAsc(transactionId);
        Map<Long, String> numbers = accountRepository
                .findAllById(entries.stream().map(LedgerEntry::getAccountId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber));

        Map<Direction, String> accountByDirection = entries.stream()
                .collect(Collectors.toMap(LedgerEntry::getDirection, e -> numbers.get(e.getAccountId())));

        return new TransactionDetailsResponse(
                txn.getId(),
                txn.getTxnType().name(),
                Money.format(txn.getAmount()),
                txn.getCurrency(),
                txn.getDescription(),
                accountByDirection.get(Direction.DEBIT),
                accountByDirection.get(Direction.CREDIT),
                txn.getReversesTxnId(),
                txn.getCreatedAt(),
                entries.stream().map(e -> new TransactionDetailsResponse.Entry(
                        e.getId(),
                        numbers.get(e.getAccountId()),
                        e.getDirection().name(),
                        Money.format(e.getAmount()),
                        Money.format(e.getBalanceAfter()),
                        e.getCreatedAt())).toList());
    }
}
