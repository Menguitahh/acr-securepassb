package com.securepass.shared.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://securepass.app/errors/";

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Credenciales invalidas");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Autenticacion requerida");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Sin permisos suficientes");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "resource-not-found", ex.getMessage());
    }

    @ExceptionHandler(StateConflictException.class)
    public ProblemDetail handleConflict(StateConflictException ex) {
        return problem(HttpStatus.CONFLICT, "state-conflict", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleViolationException ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "business-rule-violation", ex.getMessage());
        pd.setProperty("rule", ex.rule());
        return pd;
    }

    @ExceptionHandler(UserOnboardingConflictException.class)
    public ProblemDetail handleOnboardingConflict(UserOnboardingConflictException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "user-onboarding-conflict", ex.getMessage());
        pd.setProperty("code", ex.code());
        if (ex.existingUserId() != null) pd.setProperty("existingUserId", ex.existingUserId().toString());
        return pd;
    }

    @ExceptionHandler(TokenGoneException.class)
    public ResponseEntity<ProblemDetail> handleTokenGone(TokenGoneException ex) {
        // Body identico para no-encontrado, vencido y consumido: la respuesta
        // no debe permitir distinguir si el token alguna vez fue valido. Fijamos
        // `instance` a un URI generico porque el URI del request incluye el
        // token del caller y Spring lo copia si lo dejamos null.
        // Devolvemos ResponseEntity para evitar el post-procesado que hace
        // ResponseEntityExceptionHandler sobre ProblemDetail crudo.
        ProblemDetail pd = problem(HttpStatus.GONE, "token-gone", "Token invalido o vencido");
        pd.setInstance(URI.create(TYPE_BASE + "token-gone"));
        return ResponseEntity.status(HttpStatus.GONE).body(pd);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex) {
        ProblemDetail pd = problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limit-exceeded", "Demasiadas solicitudes");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfter().toSeconds()))
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-error", "Campos invalidos");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", Optional.ofNullable(fe.getDefaultMessage()).orElse("invalido")))
                .toList();
        pd.setProperty("errors", errors);
        return new ResponseEntity<>(pd, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleFallback(Exception ex) {
        LOG.error("error no manejado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Error interno");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + type));
        pd.setTitle(type);
        return pd;
    }
}
