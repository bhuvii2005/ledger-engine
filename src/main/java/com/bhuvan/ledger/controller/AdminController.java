package com.bhuvan.ledger.controller;

import com.bhuvan.ledger.dto.ReconciliationReport;
import com.bhuvan.ledger.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ReconciliationService reconciliationService;

    /** Always 200: the report itself says whether the ledger is healthy. */
    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation() {
        return reconciliationService.run();
    }
}
