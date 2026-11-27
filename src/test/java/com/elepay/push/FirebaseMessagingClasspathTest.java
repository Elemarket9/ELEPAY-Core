package com.elepay.push;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Verify the messaging-only SDK can initialize without Firestore/Storage or network credentials. */
class FirebaseMessagingClasspathTest {
    @Test void initializesMessagingWithOnlyRequiredLibraries() {
        FirebaseOptions options = FirebaseOptions.builder()
                .setProjectId("elepay-local-test")
                .setCredentials(GoogleCredentials.create(new AccessToken("local-test-only",
                        new Date(System.currentTimeMillis() + 600_000))))
                .build();
        FirebaseApp app = FirebaseApp.initializeApp(options, "fcm-classpath-" + UUID.randomUUID());
        try {
            assertThat(FirebaseMessaging.getInstance(app)).isNotNull();
        } finally {
            app.delete();
        }
    }
}
