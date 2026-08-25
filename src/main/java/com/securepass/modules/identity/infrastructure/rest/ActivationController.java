package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.ActivateAccountUseCase;
import com.securepass.modules.identity.application.LookupActivationUseCase;
import com.securepass.shared.exception.BusinessRuleViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * WBS-102: endpoints de activacion de cuenta. Consumidos por la app
 * (deep link {@code securepass://activar/{token}}) y por WBS-105
 * (recuperacion) cuando se agregue esa via.
 *
 * Toda condicion invalida (token no encontrado, vencido, ya consumido)
 * devuelve 410 GONE, sin distinguir: no queremos filtrar informacion
 * sobre tokens ajenos.
 */
@RestController
@RequestMapping("/api/v1/activation")
public class ActivationController {

    private final LookupActivationUseCase lookup;
    private final ActivateAccountUseCase activate;

    public ActivationController(LookupActivationUseCase lookup, ActivateAccountUseCase activate) {
        this.lookup = lookup;
        this.activate = activate;
    }

    @GetMapping("/{token}")
    @PreAuthorize("permitAll()")
    public ActivationLookupResponse get(@PathVariable String token) {
        LookupActivationUseCase.Result result = lookup.lookup(token);
        if (result instanceof LookupActivationUseCase.Valid v) {
            return new ActivationLookupResponse(maskedEmail(v.email()), v.fullName(), v.purpose().name(), v.expiresAt());
        }
        throw new ResponseStatusException(HttpStatus.GONE, "token invalido o vencido");
    }

    @PostMapping("/{token}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> post(@PathVariable String token, @Valid @RequestBody ActivateRequest body) {
        ActivateAccountUseCase.Result result = activate.activate(token, body.password());
        if (result == ActivateAccountUseCase.Success.INSTANCE) return ResponseEntity.noContent().build();
        if (result == ActivateAccountUseCase.Failure.WEAK_PASSWORD) {
            throw new BusinessRuleViolationException("weak-password", "La contraseña debe tener al menos 8 caracteres");
        }
        throw new ResponseStatusException(HttpStatus.GONE, "token invalido o vencido");
    }

    /**
     * Enmascara el email en la respuesta del GET: el usuario esta viendo
     * "vas a activar la cuenta de a***@dominio". Devolver el email
     * completo permitiria a alguien con el token descubrir a que cuenta
     * apunta; el hash lo asocia via lookup interno, no lo revela.
     */
    private static String maskedEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at);
    }

    public record ActivateRequest(@NotBlank String password) {}

    public record ActivationLookupResponse(String maskedEmail, String fullName, String purpose, Instant expiresAt) {}
}
