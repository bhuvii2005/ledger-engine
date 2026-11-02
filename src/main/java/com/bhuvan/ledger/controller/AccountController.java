package com.bhuvan.ledger.controller;

import com.bhuvan.ledger.dto.AccountResponse;
import com.bhuvan.ledger.dto.CreateAccountRequest;
import com.bhuvan.ledger.dto.UpdateAccountStatusRequest;
import com.bhuvan.ledger.service.AccountService;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse account = accountService.create(request.ownerName());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.accountNumber())).body(account);
    }

    @GetMapping("/{accountNumber}")
    public AccountResponse get(@PathVariable String accountNumber) {
        return accountService.get(accountNumber);
    }

    @PatchMapping(path = "/{accountNumber}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AccountResponse changeStatus(@PathVariable String accountNumber,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        return accountService.changeStatus(accountNumber, request.status());
    }
}
