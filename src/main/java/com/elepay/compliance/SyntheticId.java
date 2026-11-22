package com.elepay.compliance;

import com.elepay.risk.SyntheticIdentityService;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Algorithm 033: Synthetic identity detection.
 * Alias/delegator for SyntheticIdentityService in com.elepay.compliance for compliance namespace access.
 */
@Primary
@Service("syntheticId")
public class SyntheticId extends SyntheticIdentityService {
    public SyntheticId(JdbcClient db) {
        super(db);
    }
}
