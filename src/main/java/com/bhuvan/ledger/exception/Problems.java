package com.bhuvan.ledger.exception;

import org.springframework.http.ProblemDetail;

/** Builds the RFC 7807 bodies used for every error, thrown or stored. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
        body.setTitle(code.title());
        body.setProperty("code", code.name());
        return body;
    }

    public static ProblemDetail of(LedgerException ex) {
        ProblemDetail body = of(ex.getCode(), ex.getMessage());
        ex.getProperties().forEach(body::setProperty);
        return body;
    }
}
