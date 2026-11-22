package com.elepay.risk;

import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm 036: Chargeback prediction.
 * Alias/delegator for ChargebackPredictionService providing backward and shorthand compatibility.
 */
@Primary
@Service("chargebackPredictor")
public class ChargebackPredictor extends ChargebackPredictionService {
    public ChargebackPredictor(JdbcClient db) {
        super(db);
    }
}
