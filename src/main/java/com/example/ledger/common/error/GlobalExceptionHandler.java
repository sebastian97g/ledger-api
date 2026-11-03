package com.example.ledger.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Central RFC 9457 problem+json error handler.
 * Maps domain exceptions to appropriate HTTP statuses with stable machine-readable codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE_URI = "https://ledger.example.com/problems/";

    // --- 400 Bad Request ---

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ProblemDetail handleMissingIdempotencyKey(MissingIdempotencyKeyException ex,
                                                      HttpServletRequest req) {
        return buildProblem(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(InvalidCurrencyException.class)
    public ProblemDetail handleInvalidCurrency(InvalidCurrencyException ex,
                                               HttpServletRequest req) {
        return buildProblem(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex,
                                             HttpServletRequest req) {
        if ("Idempotency-Key".equals(ex.getHeaderName())) {
            return buildProblem(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY",
                    "The Idempotency-Key header is required for this request", req, null);
        }
        return buildProblem(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Required header missing: " + ex.getHeaderName(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletRequest req) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fe) {
                        return Map.of(
                                "pointer", "/" + fe.getField().replace(".", "/"),
                                "code", "VALIDATION_ERROR",
                                "detail", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"
                        );
                    }
                    return Map.of(
                            "pointer", "/",
                            "code", "VALIDATION_ERROR",
                            "detail", error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value"
                    );
                })
                .toList();

        ProblemDetail pd = buildProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", req, null);
        pd.setProperty("errors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex,
                                               HttpServletRequest req) {
        return buildProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), req, null);
    }

    // --- 404 Not Found ---

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex,
                                               HttpServletRequest req) {
        if (ex.getPointer() != null) {
            // Referenced inside a request body (e.g. transaction entry) → 422 with pointer
            List<Map<String, String>> errors = List.of(Map.of(
                    "pointer", ex.getPointer(),
                    "code", ex.getCode(),
                    "detail", ex.getMessage()
            ));
            return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(),
                    ex.getMessage(), req, errors);
        }
        // Top-level resource lookup → 404
        return buildProblem(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleTransactionNotFound(TransactionNotFoundException ex,
                                                   HttpServletRequest req) {
        return buildProblem(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), req, null);
    }

    // --- 409 Conflict ---

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex,
                                                   HttpServletRequest req) {
        ProblemDetail pd = buildProblem(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req, null);
        pd.setProperty("retryAfter", null);
        return pd;
    }

    @ExceptionHandler(AlreadyReversedException.class)
    public ProblemDetail handleAlreadyReversed(AlreadyReversedException ex,
                                               HttpServletRequest req) {
        return buildProblem(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req, null);
    }

    // --- 422 Unprocessable Entity ---

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ProblemDetail handleUnsupportedCurrency(UnsupportedCurrencyException ex,
                                                   HttpServletRequest req) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(UnbalancedTransactionException.class)
    public ProblemDetail handleUnbalanced(UnbalancedTransactionException ex,
                                          HttpServletRequest req) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(OverdraftExceededException.class)
    public ProblemDetail handleOverdraft(OverdraftExceededException ex,
                                         HttpServletRequest req) {
        List<Map<String, String>> errors = null;
        if (ex.getPointer() != null) {
            errors = List.of(Map.of(
                    "pointer", ex.getPointer(),
                    "code", ex.getCode(),
                    "detail", ex.getMessage()
            ));
        }
        ProblemDetail pd = buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(),
                ex.getMessage(), req, errors);
        return pd;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex,
                                                HttpServletRequest req) {
        List<Map<String, String>> errors = null;
        if (ex.getPointer() != null) {
            errors = List.of(Map.of(
                    "pointer", ex.getPointer(),
                    "code", ex.getCode(),
                    "detail", ex.getMessage()
            ));
        }
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), req, errors);
    }

    @ExceptionHandler(DecimalPrecisionException.class)
    public ProblemDetail handleDecimalPrecision(DecimalPrecisionException ex,
                                                HttpServletRequest req) {
        List<Map<String, String>> errors = null;
        if (ex.getPointer() != null) {
            errors = List.of(Map.of(
                    "pointer", ex.getPointer(),
                    "code", ex.getCode(),
                    "detail", ex.getMessage()
            ));
        }
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), req, errors);
    }

    @ExceptionHandler(CannotReverseReversalException.class)
    public ProblemDetail handleCannotReverseReversal(CannotReverseReversalException ex,
                                                     HttpServletRequest req) {
        return buildProblem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), req, null);
    }

    // --- 500 Internal Server Error (catch-all) ---

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", req, null);
    }

    // --- Builder ---

    private ProblemDetail buildProblem(HttpStatus status, String code, String detail,
                                       HttpServletRequest req,
                                       List<Map<String, String>> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(PROBLEM_BASE_URI + code.toLowerCase().replace('_', '-')));
        pd.setTitle(toTitle(code));
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", code);
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return pd;
    }

    private String toTitle(String code) {
        return code.replace('_', ' ').toLowerCase();
    }
}
