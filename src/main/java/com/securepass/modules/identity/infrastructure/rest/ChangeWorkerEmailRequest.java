package com.securepass.modules.identity.infrastructure.rest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** securepassb#181: email nuevo de un trabajador que todavia no activo su cuenta. */
public record ChangeWorkerEmailRequest(@NotBlank @Email @Size(max = 160) String email) {}
