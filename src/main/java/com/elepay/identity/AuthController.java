package com.elepay.identity;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Native authentication endpoints with dual-channel Email + SMS verification.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final NativeAuthService auth;

    public AuthController(NativeAuthService auth) {
        this.auth = auth;
    }

    public record RegisterRequest(String fullName, String email, String phone, String password) {}
    public record LoginRequest(String identifier, String password) {}
    public record VerifyRequest(UUID challengeId, String emailCode, String smsCode) {}
    public record ResendRequest(UUID challengeId) {}
    public record ForgotPasswordRequest(String identifier) {}
    public record ResetPasswordRequest(UUID challengeId, String emailCode, String smsCode, String newPassword) {}

    @PostMapping("/register")
    public NativeAuthService.ChallengeResponse register(@RequestBody RegisterRequest req) {
        return auth.register(req.fullName(), req.email(), req.phone(), req.password());
    }

    @PostMapping("/login")
    public NativeAuthService.ChallengeResponse login(@RequestBody LoginRequest req) {
        return auth.login(req.identifier(), req.password());
    }

    @PostMapping("/verify")
    public NativeAuthService.VerifyResponse verify(@RequestBody VerifyRequest req) {
        return auth.verify(req.challengeId(), req.emailCode(), req.smsCode());
    }

    @PostMapping("/resend")
    public NativeAuthService.ChallengeResponse resend(@RequestBody ResendRequest req) {
        return auth.resend(req.challengeId());
    }

    @PostMapping("/forgot-password")
    public NativeAuthService.ChallengeResponse forgotPassword(@RequestBody ForgotPasswordRequest req) {
        return auth.requestPasswordReset(req.identifier());
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody ResetPasswordRequest req) {
        auth.resetPassword(req.challengeId(), req.emailCode(), req.smsCode(), req.newPassword());
        return Map.of("reset", true);
    }
}
