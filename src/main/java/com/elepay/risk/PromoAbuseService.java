package com.elepay.risk;

import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm 031: Promotion and referral abuse detection.
 * Alias/delegator for PromotionAbuseService providing backward and shorthand compatibility.
 */
@Primary
@Service("promoAbuseService")
public class PromoAbuseService extends PromotionAbuseService {
    public PromoAbuseService(JdbcClient db) {
        super(db);
    }
}
