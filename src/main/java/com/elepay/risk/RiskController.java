package com.elepay.risk;

import com.elepay.identity.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private final RiskEngineService riskEngine;

    public RiskController(RiskEngineService riskEngine) {
        this.riskEngine = riskEngine;
    }

    private static Actor ops() {
        Actor actor = Actor.current();
        actor.requireFreezeRisk();
        return actor;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<RiskEngineService.ComprehensiveRiskAssessment> evaluateTransaction(
            @RequestBody RiskEngineService.AssessmentRequest request
    ) {
        ops();
        return ResponseEntity.ok(riskEngine.assessTransaction(request));
    }

    public record StepUpVerificationRequest(
            String challengeToken,
            String secretCode,
            StepUpAuthService.ActionPayload submittedPayload
    ) {}

    @PostMapping("/step-up/verify")
    public ResponseEntity<StepUpAuthService.VerificationResult> verifyStepUp(
            @RequestBody StepUpVerificationRequest request
    ) {
        Actor actor = ops();
        return ResponseEntity.ok(riskEngine.getStepUpAuthService().verifyChallenge(
                request.challengeToken(),
                request.secretCode(),
                request.submittedPayload(),
                actor.subject()
        ));
    }

    @PostMapping("/merchant/score")
    public ResponseEntity<MerchantRiskService.MerchantRiskEvaluation> evaluateMerchant(
            @RequestBody MerchantRiskService.MerchantMetrics metrics
    ) {
        ops();
        return ResponseEntity.ok(riskEngine.getMerchantRiskService().evaluateMerchantRisk(metrics));
    }

    @PostMapping("/mule/flow")
    public ResponseEntity<MuleGraphService.MuleNodeAssessment> recordMuleFlow(
            @RequestBody MuleGraphService.TransactionFlow flow
    ) {
        ops();
        return ResponseEntity.ok(riskEngine.getMuleGraphService().recordFlowAndAssess(flow));
    }

    @PostMapping("/synthetic/assess")
    public ResponseEntity<SyntheticIdentityService.SyntheticIdentityResult> assessSyntheticIdentity(
            @RequestBody SyntheticIdentityService.IdentityProfile profile
    ) {
        ops();
        return ResponseEntity.ok(riskEngine.getSyntheticIdentityService().assessIdentity(profile));
    }

    @PostMapping("/chargeback/predict")
    public ResponseEntity<ChargebackPredictionService.ChargebackAssessment> predictChargeback(
            @RequestBody ChargebackPredictionService.PaymentRiskProfile profile
    ) {
        ops();
        return ResponseEntity.ok(riskEngine.getChargebackService().predictChargeback(profile));
    }
}
