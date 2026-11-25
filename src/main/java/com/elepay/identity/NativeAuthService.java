package com.elepay.identity;

import com.elepay.common.ApiException;
import com.elepay.common.Idempotency;
import com.elepay.common.References;
import com.elepay.common.VerificationDelivery;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Native ELEPAY authentication with mandatory dual-channel (Email + SMS) verification
 * for account registration, sign-in (2FA), and password reset.
 */
@Service
public class NativeAuthService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final int LOCKOUT_MINUTES = 15;
    private static final int CHALLENGE_TTL_MINUTES = 10;
    private static final int MAX_CHALLENGE_ATTEMPTS = 5;
    private static final int MAX_RESENDS = 3;

    public record ChallengeResponse(
            UUID challengeId,
            String purpose,
            List<String> channels,
            String maskedEmail,
            String maskedPhone,
            int expiresInSeconds,
            boolean emailVerified,
            boolean smsVerified
    ) {}

    public record AuthUserView(
            UUID id,
            String fullName,
            String email,
            String phone,
            List<String> roles,
            boolean emailVerified,
            boolean phoneVerified
    ) {}

    public record VerifyResponse(
            boolean verified,
            boolean emailVerified,
            boolean smsVerified,
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            AuthUserView user
    ) {}

    private final JdbcClient db;
    private final VerificationDelivery verificationDelivery;
    private final Environment env;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String jwtSigningSecret;
    private final long jwtTtlSeconds;
    private final long resendCooldownSeconds;

    public NativeAuthService(
            JdbcClient db,
            VerificationDelivery verificationDelivery,
            Environment env,
            @Value("${elepay.auth.jwt-signing-secret:}") String jwtSigningSecret,
            @Value("${elepay.auth.jwt-ttl-seconds:3600}") long jwtTtlSeconds,
            @Value("${elepay.auth.resend-cooldown-seconds:30}") long resendCooldownSeconds) {
        this.db = db;
        this.verificationDelivery = verificationDelivery;
        this.env = env;
        this.jwtSigningSecret = jwtSigningSecret == null ? "" : jwtSigningSecret.trim();
        this.jwtTtlSeconds = Math.max(300, jwtTtlSeconds);
        this.resendCooldownSeconds = Math.max(0, resendCooldownSeconds);
    }

    @Transactional
    public ChallengeResponse register(String fullName, String rawEmail, String rawPhone, String password) {
        String name = fullName == null ? "" : fullName.trim();
        if (name.length() < 2 || name.length() > 120) {
            throw ApiException.badRequest("invalid_name", "Enter your full name (at least 2 characters)");
        }
        String email = normalizeEmail(rawEmail);
        String phone = normalizePhone(rawPhone);
        validatePassword(password);

        Integer existing = db.sql("SELECT COUNT(*) FROM auth_account WHERE email = :email OR phone = :phone")
                .param("email", email).param("phone", phone)
                .query(Integer.class).single();
        if (existing != null && existing > 0) {
            throw ApiException.conflict("account_exists", "An account with this email or phone number already exists");
        }

        UUID accountId = UUID.randomUUID();
        String hash = passwordEncoder.encode(password);
        db.sql("""
                INSERT INTO auth_account (id, email, phone, full_name, password_hash, roles, status, created_at, updated_at)
                VALUES (:id, :email, :phone, :name, :hash, 'user', 'pending_verification', now(), now())""")
                .param("id", accountId)
                .param("email", email)
                .param("phone", phone)
                .param("name", name)
                .param("hash", hash)
                .update();

        return createAndDispatchChallenge(accountId, email, phone, "register",
                "Verify your ELEPAY account", "account registration");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ChallengeResponse login(String rawIdentifier, String password) {
        if (rawIdentifier == null || rawIdentifier.isBlank() || password == null || password.isBlank()) {
            throw ApiException.badRequest("credentials_required", "Enter your email or phone number and password");
        }
        AccountRow account = findByIdentifier(rawIdentifier)
                .orElseThrow(() -> ApiException.unauthorized("Invalid email/phone or password"));

        if ("suspended".equalsIgnoreCase(account.status())) {
            throw ApiException.forbidden("This account is suspended. Contact ELEPAY support.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) {
            throw ApiException.forbidden("Too many failed sign-in attempts. Try again later.");
        }
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            int nextAttempts = account.failedAttempts() + 1;
            OffsetDateTime lockUntil = nextAttempts >= MAX_LOGIN_FAILURES ? now.plusMinutes(LOCKOUT_MINUTES) : null;
            db.sql("""
                    UPDATE auth_account
                    SET failed_login_attempts = :attempts, locked_until = :lockUntil, updated_at = now()
                    WHERE id = :id""")
                    .param("attempts", nextAttempts)
                    .param("lockUntil", lockUntil)
                    .param("id", account.id())
                    .update();
            throw ApiException.unauthorized("Invalid email/phone or password");
        }

        if (account.failedAttempts() > 0 || account.lockedUntil() != null) {
            db.sql("UPDATE auth_account SET failed_login_attempts = 0, locked_until = NULL, updated_at = now() WHERE id = :id")
                    .param("id", account.id()).update();
        }

        return createAndDispatchChallenge(account.id(), account.email(), account.phone(), "login",
                "ELEPAY Sign-in Verification Code", "sign-in");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public VerifyResponse verify(UUID challengeId, String emailCode, String smsCode) {
        if (challengeId == null) {
            throw ApiException.badRequest("challenge_required", "Verification challenge ID is required");
        }
        ChallengeRow ch = loadChallengeForUpdate(challengeId);
        OffsetDateTime now = OffsetDateTime.now();
        if (ch.consumedAt() != null) {
            throw ApiException.forbidden("This verification challenge has already been used");
        }
        if (ch.expiresAt().isBefore(now)) {
            throw ApiException.forbidden("Verification codes have expired. Request new codes.");
        }
        if (ch.attempts() >= ch.maxAttempts()) {
            throw ApiException.forbidden("Too many invalid verification attempts. Start again.");
        }

        boolean emailAlreadyVerified = ch.emailVerifiedAt() != null;
        boolean smsAlreadyVerified = ch.smsVerifiedAt() != null;
        boolean emailProvided = emailCode != null && !emailCode.isBlank();
        boolean smsProvided = smsCode != null && !smsCode.isBlank();

        if (!emailProvided && !smsProvided) {
            throw ApiException.badRequest("codes_required", "Enter both the email code and the SMS code");
        }

        boolean emailMatch = emailAlreadyVerified || (emailProvided && hashEquals(ch.emailCodeHash(), emailCode.trim()));
        boolean smsMatch = smsAlreadyVerified || (smsProvided && hashEquals(ch.smsCodeHash(), smsCode.trim()));

        boolean wrongAttempt = (emailProvided && !emailMatch) || (smsProvided && !smsMatch);
        if (wrongAttempt) {
            db.sql("UPDATE auth_verification_challenge SET attempts = attempts + 1 WHERE id = :id")
                    .param("id", challengeId).update();
            if (emailProvided && !emailMatch && smsProvided && !smsMatch) {
                throw ApiException.forbidden("Both the email code and SMS code are invalid");
            }
            if (emailProvided && !emailMatch) {
                throw ApiException.forbidden("Invalid email verification code");
            }
            throw ApiException.forbidden("Invalid SMS verification code");
        }

        OffsetDateTime nextEmailVerifiedAt = emailMatch ? (ch.emailVerifiedAt() != null ? ch.emailVerifiedAt() : now) : null;
        OffsetDateTime nextSmsVerifiedAt = smsMatch ? (ch.smsVerifiedAt() != null ? ch.smsVerifiedAt() : now) : null;
        boolean bothVerified = nextEmailVerifiedAt != null && nextSmsVerifiedAt != null;

        db.sql("""
                UPDATE auth_verification_challenge
                SET email_verified_at = :ev,
                    sms_verified_at = :sv,
                    consumed_at = :consumed
                WHERE id = :id""")
                .param("ev", nextEmailVerifiedAt)
                .param("sv", nextSmsVerifiedAt)
                .param("consumed", bothVerified ? now : null)
                .param("id", challengeId)
                .update();

        if (!bothVerified) {
            return new VerifyResponse(false, nextEmailVerifiedAt != null, nextSmsVerifiedAt != null,
                    null, null, 0, null);
        }

        AccountRow account = findById(ch.accountId());
        db.sql("""
                UPDATE auth_account
                SET email_verified_at = COALESCE(email_verified_at, :now),
                    phone_verified_at = COALESCE(phone_verified_at, :now),
                    status = 'active',
                    updated_at = now()
                WHERE id = :id""")
                .param("now", now)
                .param("id", account.id())
                .update();

        List<String> roles = parseRoles(account.roles());
        String token = mintJwt(account, roles);
        return new VerifyResponse(
                true,
                true,
                true,
                token,
                "Bearer",
                jwtTtlSeconds,
                new AuthUserView(account.id(), account.fullName(), account.email(), account.phone(), roles, true, true)
        );
    }

    @Transactional
    public ChallengeResponse resend(UUID challengeId) {
        if (challengeId == null) {
            throw ApiException.badRequest("challenge_required", "Verification challenge ID is required");
        }
        ChallengeRow ch = loadChallengeForUpdate(challengeId);
        if (ch.consumedAt() != null) {
            throw ApiException.forbidden("This verification challenge has already been completed");
        }
        if (ch.resendCount() >= MAX_RESENDS) {
            throw ApiException.forbidden("Maximum code resends reached. Please start sign-in again.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (resendCooldownSeconds > 0 && ch.lastSentAt() != null
                && ch.lastSentAt().plusSeconds(resendCooldownSeconds).isAfter(now)) {
            throw ApiException.badRequest("resend_cooldown",
                    "Please wait " + resendCooldownSeconds + " seconds before requesting new codes");
        }

        AccountRow account = findById(ch.accountId());
        String emailCode = References.digits(6);
        String smsCode = References.digits(6);
        OffsetDateTime expiresAt = now.plusMinutes(CHALLENGE_TTL_MINUTES);

        String purposeLabel = switch (ch.purpose()) {
            case "register" -> "account registration";
            case "reset_password" -> "password reset";
            default -> "sign-in";
        };
        var receipt = verificationDelivery.sendBoth(
                account.email(), emailCode,
                account.phone(), smsCode,
                "ELEPAY Verification Codes", purposeLabel);

        db.sql("""
                UPDATE auth_verification_challenge
                SET email_code_hash = :eh,
                    sms_code_hash = :sh,
                    email_verified_at = NULL,
                    sms_verified_at = NULL,
                    attempts = 0,
                    resend_count = resend_count + 1,
                    last_sent_at = :now,
                    expires_at = :exp
                WHERE id = :id""")
                .param("eh", Idempotency.sha256(emailCode))
                .param("sh", Idempotency.sha256(smsCode))
                .param("now", now)
                .param("exp", expiresAt)
                .param("id", challengeId)
                .update();

        return new ChallengeResponse(
                challengeId, ch.purpose(), List.of("email", "sms"),
                receipt.maskedEmail(), receipt.maskedPhone(),
                CHALLENGE_TTL_MINUTES * 60, false, false);
    }

    @Transactional
    public ChallengeResponse requestPasswordReset(String rawIdentifier) {
        if (rawIdentifier == null || rawIdentifier.isBlank()) {
            throw ApiException.badRequest("identifier_required", "Enter your email address or phone number");
        }
        var opt = findByIdentifier(rawIdentifier);
        if (opt.isEmpty()) {
            // Return a synthetic non-enumerating response shape when account is unknown.
            return new ChallengeResponse(
                    UUID.randomUUID(), "reset_password", List.of("email", "sms"),
                    "***", "****", CHALLENGE_TTL_MINUTES * 60, false, false);
        }
        AccountRow account = opt.get();
        return createAndDispatchChallenge(account.id(), account.email(), account.phone(), "reset_password",
                "ELEPAY Password Reset Codes", "password reset");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void resetPassword(UUID challengeId, String emailCode, String smsCode, String newPassword) {
        validatePassword(newPassword);
        ChallengeRow ch = loadChallengeForUpdate(challengeId);
        if (!"reset_password".equals(ch.purpose())) {
            throw ApiException.badRequest("invalid_challenge", "Invalid password reset challenge");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (ch.consumedAt() != null || ch.expiresAt().isBefore(now) || ch.attempts() >= ch.maxAttempts()) {
            throw ApiException.forbidden("Password reset challenge is invalid or expired");
        }
        boolean emailOk = emailCode != null && !emailCode.isBlank() && hashEquals(ch.emailCodeHash(), emailCode.trim());
        boolean smsOk = smsCode != null && !smsCode.isBlank() && hashEquals(ch.smsCodeHash(), smsCode.trim());
        if (!emailOk || !smsOk) {
            db.sql("UPDATE auth_verification_challenge SET attempts = attempts + 1 WHERE id = :id")
                    .param("id", challengeId).update();
            throw ApiException.forbidden("Both the email code and SMS code must be valid");
        }
        String newHash = passwordEncoder.encode(newPassword);
        db.sql("""
                UPDATE auth_account
                SET password_hash = :hash, failed_login_attempts = 0, locked_until = NULL, updated_at = now()
                WHERE id = :id""")
                .param("hash", newHash).param("id", ch.accountId()).update();
        db.sql("""
                UPDATE auth_verification_challenge
                SET email_verified_at = :now, sms_verified_at = :now, consumed_at = :now
                WHERE id = :id""")
                .param("now", now).param("id", challengeId).update();
    }

    private ChallengeResponse createAndDispatchChallenge(
            UUID accountId, String email, String phone, String purpose,
            String emailSubject, String purposeLabel) {
        UUID challengeId = UUID.randomUUID();
        String emailCode = References.digits(6);
        String smsCode = References.digits(6);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusMinutes(CHALLENGE_TTL_MINUTES);

        var receipt = verificationDelivery.sendBoth(
                email, emailCode,
                phone, smsCode,
                emailSubject, purposeLabel);

        db.sql("""
                INSERT INTO auth_verification_challenge
                    (id, account_id, purpose, email_code_hash, sms_code_hash, attempts, max_attempts,
                     resend_count, last_sent_at, expires_at, created_at)
                VALUES (:id, :accountId, :purpose, :eh, :sh, 0, :maxAttempts, 0, :now, :exp, :now)""")
                .param("id", challengeId)
                .param("accountId", accountId)
                .param("purpose", purpose)
                .param("eh", Idempotency.sha256(emailCode))
                .param("sh", Idempotency.sha256(smsCode))
                .param("maxAttempts", MAX_CHALLENGE_ATTEMPTS)
                .param("now", now)
                .param("exp", expiresAt)
                .update();

        return new ChallengeResponse(
                challengeId, purpose, List.of("email", "sms"),
                receipt.maskedEmail(), receipt.maskedPhone(),
                CHALLENGE_TTL_MINUTES * 60, false, false);
    }

    private String mintJwt(AccountRow account, List<String> roles) {
        byte[] secret = resolveSigningSecret();
        try {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder()
                    .subject(account.email())
                    .claim("uid", account.id().toString())
                    .claim("name", account.fullName())
                    .claim("email", account.email())
                    .claim("phone_number", account.phone())
                    .claim("email_verified", true)
                    .claim("phone_number_verified", true)
                    .claim("roles", roles)
                    .claim("amr", List.of("pwd", "email_otp", "sms_otp"))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(jwtTtlSeconds)))
                    .build();
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign authentication token", e);
        }
    }

    private byte[] resolveSigningSecret() {
        if (!jwtSigningSecret.isBlank()) {
            byte[] bytes = jwtSigningSecret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("elepay.auth.jwt-signing-secret must be at least 32 bytes");
            }
            return bytes;
        }
        boolean isProd = env != null && env.acceptsProfiles(Profiles.of("prod", "production"))
                && !env.acceptsProfiles(Profiles.of("test", "dev", "sandbox"));
        if (!isProd && env != null) {
            String devSecret = env.getProperty("elepay.security.dev-jwt-secret", "");
            if (!devSecret.isBlank()) {
                return devSecret.getBytes(StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Native auth JWT signing secret is not configured (set elepay.auth.jwt-signing-secret)");
    }

    private java.util.Optional<AccountRow> findByIdentifier(String rawIdentifier) {
        String trimmed = rawIdentifier.trim();
        if (trimmed.contains("@")) {
            String email = trimmed.toLowerCase(Locale.ROOT);
            return db.sql("""
                    SELECT id, email, phone, full_name, password_hash, roles, failed_login_attempts, locked_until, status
                    FROM auth_account WHERE email = :email""")
                    .param("email", email).query(this::mapAccount).optional();
        }
        String phone = normalizePhoneOrRaw(trimmed);
        return db.sql("""
                SELECT id, email, phone, full_name, password_hash, roles, failed_login_attempts, locked_until, status
                FROM auth_account WHERE phone = :phone""")
                .param("phone", phone).query(this::mapAccount).optional();
    }

    private AccountRow findById(UUID id) {
        return db.sql("""
                SELECT id, email, phone, full_name, password_hash, roles, failed_login_attempts, locked_until, status
                FROM auth_account WHERE id = :id""")
                .param("id", id).query(this::mapAccount).single();
    }

    private ChallengeRow loadChallengeForUpdate(UUID id) {
        return db.sql("""
                SELECT id, account_id, purpose, email_code_hash, sms_code_hash,
                       email_verified_at, sms_verified_at, attempts, max_attempts,
                       resend_count, last_sent_at, expires_at, consumed_at
                FROM auth_verification_challenge
                WHERE id = :id FOR UPDATE""")
                .param("id", id)
                .query((rs, i) -> new ChallengeRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("purpose"),
                        rs.getString("email_code_hash"),
                        rs.getString("sms_code_hash"),
                        rs.getObject("email_verified_at", OffsetDateTime.class),
                        rs.getObject("sms_verified_at", OffsetDateTime.class),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        rs.getInt("resend_count"),
                        rs.getObject("last_sent_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("consumed_at", OffsetDateTime.class)
                ))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Verification challenge not found"));
    }

    private AccountRow mapAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AccountRow(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getString("roles"),
                rs.getInt("failed_login_attempts"),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getString("status")
        );
    }

    static String normalizeEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(email).matches() || email.length() > 254) {
            throw ApiException.badRequest("invalid_email", "Enter a valid email address");
        }
        return email;
    }

    static String normalizePhone(String raw) {
        String normalized = normalizePhoneOrRaw(raw);
        if (!E164_PATTERN.matcher(normalized).matches()) {
            throw ApiException.badRequest("invalid_phone", "Enter a valid phone number (e.g. +233241234567 or 0241234567)");
        }
        return normalized;
    }

    private static String normalizePhoneOrRaw(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceAll("[\\s\\-()]", "");
        if (cleaned.startsWith("0") && cleaned.length() == 10 && cleaned.chars().allMatch(Character::isDigit)) {
            return "+233" + cleaned.substring(1);
        }
        if (cleaned.startsWith("233") && cleaned.length() == 12 && cleaned.chars().allMatch(Character::isDigit)) {
            return "+" + cleaned;
        }
        return cleaned;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw ApiException.badRequest("weak_password", "Password must be at least 8 characters long");
        }
    }

    private static boolean hashEquals(String storedHex, String rawCode) {
        if (storedHex == null || rawCode == null) return false;
        String presented = Idempotency.sha256(rawCode);
        return MessageDigest.isEqual(
                storedHex.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) return List.of("user");
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private record AccountRow(
            UUID id, String email, String phone, String fullName, String passwordHash,
            String roles, int failedAttempts, OffsetDateTime lockedUntil, String status) {}

    private record ChallengeRow(
            UUID id, UUID accountId, String purpose, String emailCodeHash, String smsCodeHash,
            OffsetDateTime emailVerifiedAt, OffsetDateTime smsVerifiedAt,
            int attempts, int maxAttempts, int resendCount,
            OffsetDateTime lastSentAt, OffsetDateTime expiresAt, OffsetDateTime consumedAt) {}
}
