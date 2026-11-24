package com.elepay.common;

/**
 * Abstraction for envelope-encrypting and unwrapping sensitive credentials at rest.
 * Implemented by {@code KmsSecretProvider} in {@code com.elepay.security}.
 */
public interface SecretProtector {
    String wrapSecret(String plaintext, String aad);

    String unwrapSecret(String configuredOrStoredValue, String expectedAad);
}
