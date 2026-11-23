package com.elepay.emtech;

import java.util.List;

/** A payload that must not be sent. The sandbox coerces wrong types, so this is rejected locally. */
public class EmtechValidationException extends RuntimeException {
    private final List<String> errors;

    public EmtechValidationException(String field, String message) {
        this(List.of(field + ": " + message));
    }

    public EmtechValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
