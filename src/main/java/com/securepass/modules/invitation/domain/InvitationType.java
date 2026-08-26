package com.securepass.modules.invitation.domain;

public enum InvitationType {
    SINGLE("S"),
    EVENT("E"),
    RECURRING("R");

    private final String code;

    InvitationType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static InvitationType fromCode(String code) {
        if (code == null) throw new IllegalArgumentException("code requerido");
        for (InvitationType t : values()) if (t.code.equals(code)) return t;
        throw new IllegalArgumentException("codigo de tipo desconocido: " + code);
    }
}
