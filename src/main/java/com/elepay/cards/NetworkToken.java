package com.elepay.cards;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NetworkToken(
        UUID id,
        UUID cardId,
        String tokenPan,
        String tokenRequesterId,
        String deviceId,
        int expiryMonth,
        int expiryYear,
        TokenStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
