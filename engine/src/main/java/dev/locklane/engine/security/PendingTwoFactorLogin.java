package dev.locklane.engine.security;

/**
 * The session attribute key that carries a login through the gap between a verified
 * password and a verified TOTP code (#89). {@link TwoFactorAwareLoginSuccessHandler}
 * writes it in place of the full session Spring Security's login filter would
 * otherwise have established; {@link AuthController#verifyTwoFactor} reads it back
 * and clears it once the code checks out.
 */
final class PendingTwoFactorLogin {

    static final String SESSION_ATTRIBUTE = "pending2faUsername";

    private PendingTwoFactorLogin() {
    }
}
