package com.securepass.modules.shift.application;

import com.securepass.modules.shift.domain.Shift;

/** Fila del listado admin de turnos (US-33): el turno mas lo que el dashboard muestra sin lookups extra. */
public record ShiftSummary(Shift shift, String guardFullName, String gateName, long accessCount) {}
