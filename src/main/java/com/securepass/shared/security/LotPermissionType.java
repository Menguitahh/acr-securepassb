package com.securepass.shared.security;

/**
 * WBS-108 PR2 tramo 1: tipos de permiso sobre un lote, ordenados de mas a
 * menos permisivo. La comparacion la hace {@link #atLeast(LotPermissionType)}
 * — no exponer {@link Enum#ordinal()} directo a los callers.
 *
 * El orden refleja la decision del relevamiento: OWNER puede lo que puede
 * MEMBER, MEMBER lo que puede WORKER. En un lote con OWNER+MEMBER para el
 * mismo user (que la migracion WBS-106 nunca produce, pero UNIQUE permite),
 * gana OWNER.
 */
public enum LotPermissionType {
    OWNER,
    MEMBER,
    WORKER;

    public boolean atLeast(LotPermissionType minimum) {
        return this.ordinal() <= minimum.ordinal();
    }
}
