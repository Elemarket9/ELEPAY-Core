package com.elepay.emtech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elepay.common.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

class EmtechDispatcherTest {
    private final EmtechSettings settings = Mockito.mock(EmtechSettings.class);
    private final EmtechOutbox outbox = Mockito.mock(EmtechOutbox.class);
    private final EmtechClient client = Mockito.mock(EmtechClient.class);
    private final EmtechDispatcher dispatcher = new EmtechDispatcher(settings, outbox, client, new ObjectMapper());
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void enabled() {
        when(settings.enabled()).thenReturn(true);
        when(settings.emoneyAccountType()).thenReturn("");
        when(settings.retryBaseMs()).thenReturn(0L);
        when(settings.maxAttempts()).thenReturn(8);
        when(outbox.claim(anyInt(), any())).thenReturn(List.of(id));
        when(outbox.find(id)).thenReturn(Optional.of(new EmtechOutbox.Row(
                id, 1, "device:pos-1", "DEVICE", "device", "pos-1",
                "{\"deviceId\":\"pos-1\",\"deviceType\":\"POS\",\"city\":\"Accra\",\"region\":\"GH-AA\",\"country\":\"GH\","
                        + "\"latitude\":\"5.6\",\"longitude\":\"-0.18\",\"inserviceDate\":\"2026-01-15T09:00:00Z\","
                        + "\"connectionType\":\"NETWORK\"}",
                "SENDING", 0, null, null, null)));
    }

    @Test
    void blockedAddressReleasesTheClaimAsADeadLetter() {
        when(client.submit(eq("DEVICE"), any())).thenThrow(ApiException.badRequest("unsafe_url",
                "The host api.emtech.com resolved to 198.18.0.10, which is in a blocked address range"));
        assertThat(dispatcher.dispatchAvailable(1)).isZero();
        verify(outbox).markRejected(eq(id), eq(1), eq(0), eq("unsafe_url"),
                org.mockito.ArgumentMatchers.contains("198.18.0.10"));
        verify(outbox, never()).markSubmitted(any(), anyInt());
    }

    @Test
    void tokenNotFoundIsPermanentAndDnsMissIsRetried() {
        when(client.submit(eq("DEVICE"), any()))
                .thenThrow(new IllegalStateException("Emtech token request failed with HTTP 404"));
        dispatcher.dispatchAvailable(1);
        verify(outbox).markRejected(eq(id), eq(1), eq(0), eq("token"),
                org.mockito.ArgumentMatchers.contains("HTTP 404"));

        when(client.submit(eq("DEVICE"), any()))
                .thenThrow(new IllegalStateException("Emtech token request failed with HTTP 503"));
        dispatcher.dispatchAvailable(1);
        verify(outbox).markRetry(eq(id), eq(1), eq(0), eq("retry"),
                org.mockito.ArgumentMatchers.contains("HTTP 503"), anyLong(), eq(8));
    }

    @Test
    void firstPollWaitsForAConfigurableInitialDelay() throws Exception {
        Scheduled scheduled = EmtechDispatcher.class.getDeclaredMethod("poll").getAnnotation(Scheduled.class);
        assertThat(scheduled.initialDelayString()).isEqualTo("${elepay.emtech.poll-initial-delay-ms:15000}");
        assertThat(scheduled.fixedDelayString()).contains("poll-ms");
        assertThat(new EmtechSettings().pollInitialDelayMs()).isEqualTo(15000L);
    }
}
