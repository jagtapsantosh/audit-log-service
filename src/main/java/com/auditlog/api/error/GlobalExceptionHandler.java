package com.auditlog.api.error;

import com.auditlog.domain.AuditQueryService.InvalidAuditQueryException;
import com.auditlog.domain.AuditRecordNotFoundException;
import com.auditlog.domain.IdempotencyConflictException;
import com.auditlog.domain.InvalidAuditEventException;
import com.auditlog.domain.InvalidComplianceRequestException;
import com.auditlog.domain.InvalidExportRequestException;
import com.auditlog.domain.InvalidRedactionException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single error envelope for the audit APIs: {@code {error, code, timestamp}}. Messages describe what
 * the caller got wrong and never echo credentials or internal state.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidationFailure(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return badRequest(detail.isBlank() ? "Request validation failed" : detail, "VALIDATION_ERROR");
    }

    @ExceptionHandler(InvalidAuditEventException.class)
    public ResponseEntity<ErrorResponse> onInvalidEvent(InvalidAuditEventException ex) {
        return badRequest(ex.getMessage(), ex.code());
    }

    @ExceptionHandler(InvalidAuditQueryException.class)
    public ResponseEntity<ErrorResponse> onInvalidQuery(InvalidAuditQueryException ex) {
        return badRequest(ex.getMessage(), "INVALID_QUERY");
    }

    @ExceptionHandler(InvalidRedactionException.class)
    public ResponseEntity<ErrorResponse> onInvalidRedaction(InvalidRedactionException ex) {
        return badRequest(ex.getMessage(), ex.code());
    }

    @ExceptionHandler(InvalidExportRequestException.class)
    public ResponseEntity<ErrorResponse> onInvalidExport(InvalidExportRequestException ex) {
        return badRequest(ex.getMessage(), ex.code());
    }

    @ExceptionHandler(InvalidComplianceRequestException.class)
    public ResponseEntity<ErrorResponse> onInvalidCompliance(InvalidComplianceRequestException ex) {
        return badRequest(ex.getMessage(), ex.code());
    }

    @ExceptionHandler(AuditRecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> onRecordNotFound(AuditRecordNotFoundException ex) {
        return status(HttpStatus.NOT_FOUND, ex.getMessage(), "RECORD_NOT_FOUND");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> onIdempotencyConflict(IdempotencyConflictException ex) {
        return status(HttpStatus.CONFLICT, ex.getMessage(), "IDEMPOTENCY_KEY_REUSED");
    }

    /**
     * Backstop for the unique constraint on {@code (audit_record_id, field_path)}. The service already
     * treats a repeat redaction as a no-op, so reaching this means two concurrent requests raced.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> onDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Rejected request that violated a database constraint", ex);
        return status(HttpStatus.CONFLICT, "Request conflicts with the current state of the record",
                "CONFLICT");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Rejected unreadable request body", ex);
        return badRequest("Request body is missing, malformed, or contains unknown fields",
                "MALFORMED_REQUEST");
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> onBadParameter(Exception ex) {
        log.debug("Rejected request parameter", ex);
        return badRequest("Request parameter is missing or not in the expected format", "INVALID_PARAMETER");
    }

    /**
     * Covers PUT/PATCH/DELETE attempts against audit paths: the log is append-only, so those verbs
     * are simply not mapped.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> onMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return status(HttpStatus.METHOD_NOT_ALLOWED,
                "This resource does not support " + ex.getMethod() + "; the audit log is append-only",
                "METHOD_NOT_ALLOWED");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> onUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        log.debug("Rejected unsupported media type", ex);
        return status(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", "UNSUPPORTED_MEDIA_TYPE");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> onNoResource(NoResourceFoundException ex) {
        return status(HttpStatus.NOT_FOUND, "No such resource", "NOT_FOUND");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> onAccessDenied(AccessDeniedException ex) {
        return status(HttpStatus.FORBIDDEN, "Forbidden", "FORBIDDEN");
    }

    /** Any other Spring MVC exception that already carries a status keeps it, in our envelope. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> onErrorResponse(ErrorResponseException ex) {
        log.debug("Request failed with {}", ex.getStatusCode(), ex);
        HttpStatusCode statusCode = ex.getStatusCode();
        return ResponseEntity.status(statusCode)
                .body(ErrorResponse.of("Request could not be processed", "REQUEST_FAILED"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception ex) {
        log.error("Unhandled failure serving audit request", ex);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "INTERNAL_ERROR");
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message, String code) {
        return status(HttpStatus.BAD_REQUEST, message, code);
    }

    private static ResponseEntity<ErrorResponse> status(HttpStatus status, String message, String code) {
        return ResponseEntity.status(status).body(ErrorResponse.of(message, code));
    }
}
