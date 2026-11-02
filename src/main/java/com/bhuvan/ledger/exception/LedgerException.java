package com.bhuvan.ledger.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class LedgerException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public LedgerException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public LedgerException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }

    public static LedgerException accountNotFound(String accountNumber) {
        return new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "No account with number " + accountNumber)
                .with("accountNumber", accountNumber);
    }

    public LedgerException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }
}
