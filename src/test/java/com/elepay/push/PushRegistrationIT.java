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
    @Autowired com.elepay.merchanttools.RestaurantNotificationAccess restaurantAccess;

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

    private UUID branch(UUID merchant, String name) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO merchant_branch(id,merchant_id,name) VALUES(:id,:m,:n)")
                .param("id",id).param("m",merchant).param("n",name).update();
        return id;
    }

    private UUID station(UUID merchant, UUID branch, String name) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO restaurant_kitchen_station(id,merchant_id,branch_id,name) VALUES(:id,:m,:b,:n)")
                .param("id",id).param("m",merchant).param("b",branch).param("n",name).update();
        return id;
    }

    private UUID kitchenSubscription(UUID merchant, UUID station) {
        var code = kitchen.createCode(merchant,station,"system");
        var paired = kitchen.pair(code.merchantCode(),code.code(),"Test kitchen");
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO push_subscription(id,merchant_id,subject,app,installation_id,token,station_id,kitchen_credential_id,expires_at)
                VALUES(:id,:m,:s,'KDS',:i,:t,:st,:k,now()+interval '1 hour')
                """).param("id",id).param("m",merchant).param("s","apikey:"+paired.deviceId())
                .param("i",UUID.randomUUID()).param("t",token()).param("st",station)
                .param("k",paired.deviceId()).update();
        return id;
    }

    private long queued(UUID subscription, String eventKey) {
        return db.sql("SELECT count(*) FROM push_delivery WHERE subscription_id=:s AND event_key=:k")
                .param("s",subscription).param("k",eventKey).query(Long.class).single();
    }

    @Autowired com.elepay.merchanttools.restaurant.RestaurantPushState restaurant;

    @Test void kitchenRoutingAndUnroutedFallbackAreBranchIsolated() {
        UUID m = merchant("owner-"+UUID.randomUUID());
        UUID other = merchant("owner-"+UUID.randomUUID());
        UUID a = branch(m,"A"), b = branch(m,"B");
        UUID stationA = station(m,a,"Kitchen A"), stationB = station(m,b,"Kitchen B");
        UUID subA = kitchenSubscription(m,stationA), subB = kitchenSubscription(m,stationB);
        UUID subOther = kitchenSubscription(other,station(other,branch(other,"Other"),"Kitchen"));
        UUID order = UUID.randomUUID(), item = UUID.randomUUID(), ticket = UUID.randomUUID();
        db.sql("""
                INSERT INTO pos_order(id,order_number,merchant_id,branch_id,subtotal_pesewas,total_pesewas)
                VALUES(:id,:n,:m,:b,1000,1000)
                """).param("id",order).param("n","push-"+order).param("m",m).param("b",a).update();
        db.sql("""
                INSERT INTO pos_order_item(id,order_id,item_name,quantity,unit_price_pesewas,total_pesewas)
                VALUES(:id,:o,'Test',1,1000,1000)
                """).param("id",item).param("o",order).update();
        db.sql("INSERT INTO restaurant_kds_ticket(id,merchant_id,order_id) VALUES(:id,:m,:o)")
                .param("id",ticket).param("m",m).param("o",order).update();
        db.sql("""
                INSERT INTO restaurant_kds_item(id,ticket_id,order_item_id,item_name,quantity,station_id)
                VALUES(:id,:t,:i,'Test',1,NULL)
                """).param("id",UUID.randomUUID()).param("t",ticket).param("i",item).update();
        String event = "kitchen:"+ticket;
        assertThat(queued(subA,event)).isEqualTo(1);
        assertThat(queued(subB,event)).isZero();
        assertThat(queued(subOther,event)).isZero();
        assertThat(restaurant.kitchenOrderPending(m,ticket,stationA)).isTrue();
        assertThat(restaurant.kitchenOrderPending(m,ticket,stationB)).isFalse();
        db.sql("UPDATE restaurant_kds_ticket SET status='CANCELLED' WHERE id=:id").param("id",ticket).update();
        assertThat(restaurant.kitchenOrderPending(m,ticket,stationA)).isFalse();
    }

    @Test void oversizedTokenRejectedBeforeDatabaseIndexWrite() {
        authenticate("owner-"+UUID.randomUUID());
        assertThatThrownBy(() -> new PushRegistration(db,merchants,restaurantAccess,true,168).register(
                new PushRegistration.Request(UUID.randomUUID(),UUID.randomUUID(),"POS","a".repeat(1025),null),false))
                .isInstanceOf(ApiException.class).hasMessageContaining("Invalid notification registration");
    }

    @Test void disabledRegistrationFailsClosed() {
        assertThatThrownBy(() -> new PushRegistration(db,merchants,restaurantAccess,false,168).register(null,false))
                .isInstanceOf(ApiException.class).hasMessageContaining("disabled");
    }

    @Test void registrationRotationMembershipAndLogout() {
        String owner = "push-owner-" + UUID.randomUUID();
        UUID m = merchant(owner);
        UUID unrelated = merchant("other-" + UUID.randomUUID());
        UUID installation = UUID.randomUUID();
        authenticate(owner);
        PushRegistration registration = new PushRegistration(db,merchants,restaurantAccess,true,168);
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
