package com.securepass.modules.access.infrastructure.rest;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CloseStayRequest(@NotNull UUID gateId) {}
