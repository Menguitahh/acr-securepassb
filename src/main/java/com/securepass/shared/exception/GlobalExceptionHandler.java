package com.securepass.shared.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.securepass.shared.crypto.UnreadableCiphertextException;
import org.springframework.dao.DataIntegrityViolationException;
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
        if (ex.suspension() != null) pd.setProperty("suspension", ex.suspension());
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

    @ExceptionHandler(AgeUnderMinimumException.class)
    public ProblemDetail handleAgeUnderMinimum(AgeUnderMinimumException ex) {
        // 400 con guia: el titular tiene que ir al padron de menores. No usamos
        // 422 (BR-violation) porque la app tiene que ramificar el formulario.
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST,
                "age-under-minimum",
                "El familiar es menor de " + ex.minAge() + "; cargalo en el padron de menores del lote");
        pd.setProperty("code", "AGE_UNDER_16");
        pd.setProperty("minAge", ex.minAge());
        pd.setProperty("redirectTo", "/api/v1/family/lots/" + ex.lotId() + "/minors");
        return pd;
    }

    @ExceptionHandler(AgeOverMaximumException.class)
    public ProblemDetail handleAgeOverMaximum(AgeOverMaximumException ex) {
        // Flujo espejo de AGE_UNDER_16 (WBS-110): mayor de la cota va al
        // alta de familiar, no al padron de menores.
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST,
                "age-over-maximum",
                "La persona tiene " + ex.maxAge() + " o mas anios; agregala como familiar en members");
        pd.setProperty("code", "AGE_OVER_16");
        pd.setProperty("maxAge", ex.maxAge());
        pd.setProperty("redirectTo", "/api/v1/family/lots/" + ex.lotId() + "/members");
        return pd;
    }

    @ExceptionHandler(InvalidLotChoiceException.class)
    public ProblemDetail handleInvalidLotChoice(InvalidLotChoiceException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "invalid-lot", ex.getMessage());
        pd.setProperty("code", "INVALID_LOT");
        return pd;
    }

    @ExceptionHandler(EmailRequiredException.class)
    public ProblemDetail handleEmailRequired(EmailRequiredException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "email-required", ex.getMessage());
        pd.setProperty("code", "EMAIL_REQUIRED");
        return pd;
    }

    @ExceptionHandler(NeighborhoodNotProvisionedException.class)
    public ResponseEntity<ProblemDetail> handleNeighborhoodNotProvisioned(
            NeighborhoodNotProvisionedException ex) {
        // 503 sobre 409: el cliente no puede corregir el request, la accion la
        // tiene que hacer un operador (crear signing key, politica de extension,
        // etc). Sin Retry-After: no se resuelve solo.
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE,
                "neighborhood-not-provisioned",
                "El barrio no esta aprovisionado para esta operacion");
        pd.setProperty("code", "NEIGHBORHOOD_NOT_PROVISIONED");
        pd.setProperty("neighborhoodId", ex.neighborhoodId().toString());
        pd.setProperty("missing", ex.resource());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
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

    // #157 E: el server no puede leer su propia clave de firma (master key
    // rotada o fila corrupta). El cliente no lo arregla; se loguea en ERROR
    // y el codigo distingue esto de un bug.
    @ExceptionHandler(UnreadableCiphertextException.class)
    public ProblemDetail handleUnreadableCiphertext(UnreadableCiphertextException ex) {
        LOG.error("secreto ilegible", ex);
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "signing-key-unreadable",
                "No se pudo firmar el pase. Avisá al administrador del barrio.");
        pd.setProperty("code", "SIGNING_KEY_UNREADABLE");
        return pd;
    }

    // #157 C / #207: un invariante de dominio roto por el input (record o
    // value object que valida en el constructor) es error del cliente, no
    // 500. Se loguea en WARN con stack: si fuera un bug propio, queda rastro.
    // securepassb#258: nombre o slug de barrio repetido al crearlo.
    @ExceptionHandler(com.securepass.modules.neighborhood.application.NeighborhoodAlreadyExistsException.class)
    public ProblemDetail handleNeighborhoodExists(
            com.securepass.modules.neighborhood.application.NeighborhoodAlreadyExistsException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "neighborhood-exists", ex.getMessage());
        pd.setProperty("code", "slug".equals(ex.by()) ? "SLUG_TAKEN" : "NAME_TAKEN");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        LOG.warn("request invalido: {}", ex.getMessage(), ex);
        return problem(HttpStatus.BAD_REQUEST, "validation-error",
                Optional.ofNullable(ex.getMessage()).orElse("Datos invalidos"));
    }

    // #157 D: UNIQUE / FK / CHECK sin traductor dedicado. Sin el detalle del
    // driver: puede traer valores de otras filas.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        LOG.warn("violacion de integridad", ex);
        return problem(HttpStatus.CONFLICT, "conflict",
                "El dato choca con uno existente o referencia algo que no existe");
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
