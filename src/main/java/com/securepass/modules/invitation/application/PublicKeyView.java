package com.securepass.modules.invitation.application;

public record PublicKeyView(String kid, byte[] publicKey) {
    public PublicKeyView {
        if (kid == null || kid.isBlank()) throw new IllegalArgumentException("kid requerido");
        if (publicKey == null || publicKey.length != 32) throw new IllegalArgumentException("publicKey debe ser 32 bytes");
    }
}
