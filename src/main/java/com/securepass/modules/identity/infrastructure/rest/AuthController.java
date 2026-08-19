package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.LoginUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;

    public AuthController(LoginUseCase loginUseCase) {
        this.loginUseCase = loginUseCase;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        LoginUseCase.Result result = loginUseCase.login(request.identifier(), request.password());
        if (result instanceof LoginUseCase.Success success) return LoginResponse.from(success);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid-credentials");
    }
}
