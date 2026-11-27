package com.elepay.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "elepay.push.enabled", havingValue = "true")
class FirebasePushConfiguration {
    @Bean(destroyMethod = "delete")
    FirebaseApp elepayPushApp(@Value("${elepay.push.project-id:}") String projectId) throws IOException {
        if (!projectId.matches("[a-z][a-z0-9-]{4,62}"))
            throw new IllegalStateException("Configure elepay.push.project-id before enabling FCM");
        return FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(projectId)
                .setConnectTimeout(3000)
                .setReadTimeout(5000)
                .build(), "elepay-push");
    }

    @Bean
    FirebaseMessaging elepayFirebaseMessaging(FirebaseApp elepayPushApp) {
        return FirebaseMessaging.getInstance(elepayPushApp);
    }
}
