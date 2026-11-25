package com.elepay.identity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Native authentication endpoints with dual-channel Email + SMS verification,
 * authoritative caller profile (/me), and ops_admin role management.
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
    public record UpdateAccountRolesRequest(List<String> roles, String status) {}

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

    @GetMapping("/me")
    public NativeAuthService.CallerProfile me() {
        return auth.currentProfile(Actor.current());
    }

    @GetMapping("/admin/accounts")
    public List<NativeAuthService.AuthUserView> listAccounts() {
        return auth.listAccounts(Actor.current());
    }

    @PatchMapping("/admin/accounts/{id}/roles")
    public NativeAuthService.AuthUserView updateAccountRoles(
            @PathVariable UUID id,
            @RequestBody UpdateAccountRolesRequest req) {
        return auth.updateAccountRolesAndStatus(Actor.current(), id, req.roles(), req.status());
    }
}
