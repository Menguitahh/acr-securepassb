package com.securepass.shared.crypto;

public record Ed25519KeyPair(byte[] publicKey, byte[] privateKey) {
    public Ed25519KeyPair {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("publicKey debe ser 32 bytes");
        }
        if (privateKey == null || privateKey.length != 32) {
            throw new IllegalArgumentException("privateKey debe ser 32 bytes (seed)");
        }
    }
}
