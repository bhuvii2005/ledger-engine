package com.bhuvan.ledger.exception;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ProblemDetail> handleLedger(LedgerException ex) {
        return withRetryAfterIfNeeded(ex.getCode(), Problems.of(ex));
    }

    @ExceptionHandler({
            TransientDataAccessException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseBusy(Exception ex) {
        log.warn("Database busy or unavailable: {}", ex.getClass().getSimpleName());
        return withRetryAfterIfNeeded(ErrorCode.SYSTEM_BUSY,
                Problems.of(ErrorCode.SYSTEM_BUSY, "The system is busy, please retry shortly"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        log.error("Unexpected error, correlationId={}", correlationId, ex);
        ProblemDetail body = Problems.of(ErrorCode.INTERNAL_ERROR,
                "Something went wrong. Quote the correlationId if you report this.");
        body.setProperty("correlationId", correlationId);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        ProblemDetail body = Problems.of(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getAllErrors().stream()
                .map(e -> String.valueOf(e.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Problems.of(ErrorCode.VALIDATION_FAILED, detail));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() == null ? "parameter" : "'" + ex.getPropertyName() + "'";
        return ResponseEntity.badRequest()
                .body(Problems.of(ErrorCode.VALIDATION_FAILED, "Invalid value for " + name));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(ErrorCode.MALFORMED_REQUEST, describeUnreadable(ex.getCause())));
    }

    // Spring's own handlers (unknown route, wrong method, wrong content type...) end up
    // here; make sure they carry a code like every other error response.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // super builds the ProblemDetail when body is null, so add the code afterwards
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", fallbackCode(statusCode).name());
        }
        return response;
    }

    private static String describeUnreadable(Throwable cause) {
        if (cause instanceof UnrecognizedPropertyException e) {
            return "Unknown field '" + e.getPropertyName() + "'";
        }
        if (cause instanceof InvalidFormatException e && !e.getPath().isEmpty()) {
            // Jackson uses this both for a refused number->string coercion and for a bad enum value
            return e.getTargetType() == String.class
                    ? "Field '" + path(e) + "' must be a JSON string"
                    : "Field '" + path(e) + "' has an invalid value";
        }
        if (cause instanceof MismatchedInputException e && !e.getPath().isEmpty()) {
            return "Field '" + path(e) + "' has the wrong type";
        }
        if (cause instanceof JsonParseException) {
            return "Request body is not valid JSON";
        }
        return "Request body is missing or has the wrong shape";
    }

    private static String path(JsonMappingException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static ErrorCode fallbackCode(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.MALFORMED_REQUEST;
    }

    private static ResponseEntity<ProblemDetail> withRetryAfterIfNeeded(ErrorCode code, ProblemDetail body) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
        if (code == ErrorCode.SYSTEM_BUSY || code == ErrorCode.REQUEST_IN_PROGRESS) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(body);
    }
}
