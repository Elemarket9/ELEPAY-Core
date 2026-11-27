package com.elepay.push;

import com.elepay.PostgresTestBase;
import com.elepay.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the real registration database contract without connecting to Firebase. */
@Transactional
class PushRegistrationIT extends PostgresTestBase {
    @Autowired JdbcClient db;
    @Autowired com.elepay.merchant.MerchantAccess merchants;
    @Autowired com.elepay.merchanttools.restaurant.KitchenDeviceService kitchen;

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private UUID merchant(String subject) {
        UUID id = UUID.randomUUID();
        int inserted = 0;
        for (int i = 0; i < 20 && inserted == 0; i++) {
            String code = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));
            inserted = db.sql("""
                    INSERT INTO merchant(id,legal_name,trading_name,kind,status,contact_phone,merchant_code,created_by)
                    VALUES(:id,'Push test','Push test','merchant','active','0241234567',:code,'system')
                    ON CONFLICT DO NOTHING
                    """).param("id",id).param("code",code).update();
        }
        assertThat(inserted).isEqualTo(1);
        db.sql("INSERT INTO merchant_member(merchant_id,subject,role) VALUES(:m,:s,'owner')")
                .param("m",id).param("s",subject).update();
        return id;
    }

    private void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg","none").subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private String token() { return "test:" + UUID.randomUUID(); }

    @Test void disabledRegistrationFailsClosed() {
        assertThatThrownBy(() -> new PushRegistration(db,merchants,kitchen,false,168).register(null,false))
                .isInstanceOf(ApiException.class).hasMessageContaining("disabled");
    }

    @Test void registrationRotationMembershipAndLogout() {
        String owner = "push-owner-" + UUID.randomUUID();
        UUID m = merchant(owner);
        UUID unrelated = merchant("other-" + UUID.randomUUID());
        UUID installation = UUID.randomUUID();
        authenticate(owner);
        PushRegistration registration = new PushRegistration(db,merchants,kitchen,true,168);
        String first = token();
        var request = new PushRegistration.Request(m,installation,"POS",first,null);
        registration.register(request,false);
        UUID initial = db.sql("SELECT id FROM push_eligible_subscription WHERE token=:t")
                .param("t",first).query(UUID.class).single();
        registration.register(request,false);
        assertThat(db.sql("SELECT id FROM push_subscription WHERE token=:t")
                .param("t",first).query(UUID.class).single()).isEqualTo(initial);

        assertThatThrownBy(() -> registration.register(
                new PushRegistration.Request(unrelated,installation,"POS",token(),null),false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> registration.register(
                new PushRegistration.Request(m,installation,"WAITER",token(),null),false))
                .isInstanceOf(ApiException.class);

        db.sql("INSERT INTO push_delivery(subscription_id,event_key,kind) VALUES(:s,'test:rotation','BILL_REQUESTED')")
                .param("s",initial).update();
        String replacement = token();
        registration.register(new PushRegistration.Request(m,installation,"POS",replacement,null),false);
        assertThat(db.sql("SELECT count(*) FROM push_subscription WHERE id=:id")
                .param("id",initial).query(Long.class).single()).isZero();
        assertThat(db.sql("SELECT count(*) FROM push_delivery d JOIN push_eligible_subscription s ON s.id=d.subscription_id WHERE d.subscription_id=:id")
                .param("id",initial).query(Long.class).single()).isZero();
        // A different installation cannot steal a currently registered token.
        assertThatThrownBy(() -> registration.register(new PushRegistration.Request(m,UUID.randomUUID(),"POS",replacement,null),false))
                .isInstanceOf(ApiException.class).hasMessageContaining("another installation");
        db.sql("DELETE FROM merchant_member WHERE merchant_id=:m AND subject=:s")
                .param("m",m).param("s",owner).update();
        assertThat(db.sql("SELECT count(*) FROM push_eligible_subscription WHERE token=:t")
                .param("t",replacement).query(Long.class).single()).isZero();
        registration.unregister(new PushRegistration.Removal(m,installation,"POS"),false);
        assertThat(db.sql("SELECT count(*) FROM push_subscription WHERE token=:t")
                .param("t",replacement).query(Long.class).single()).isZero();
    }
}
