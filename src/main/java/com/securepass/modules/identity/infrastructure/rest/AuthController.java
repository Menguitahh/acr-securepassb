package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.LoginUseCase;
import com.securepass.modules.identity.application.LogoutUseCase;
import com.securepass.modules.identity.application.RefreshTokenUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshUseCase;
    private final LogoutUseCase logoutUseCase;

    public AuthController(
            LoginUseCase loginUseCase,
            RefreshTokenUseCase refreshUseCase,
            LogoutUseCase logoutUseCase) {
        this.loginUseCase = loginUseCase;
        this.refreshUseCase = refreshUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        LoginUseCase.Result result = loginUseCase.login(request.identifier(), request.password());
        if (result instanceof LoginUseCase.Success success) return LoginResponse.from(success);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid-credentials");
    }

    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshTokenUseCase.Result result = refreshUseCase.refresh(request.refreshToken());
        if (result instanceof RefreshTokenUseCase.Success success) return RefreshResponse.from(success);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid-refresh");
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        logoutUseCase.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
