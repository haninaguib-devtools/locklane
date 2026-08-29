package dev.locklane.engine.security;

/**
 * The session attribute key that carries a login through the gap between a verified
 * (temporary) password and a freshly chosen one, for an account created with {@code
 * must_change_password} set (#238, #241) -- mirrors {@link PendingTwoFactorLogin}'s shape
 * for the 2FA gate (#89), just staging a different second step. {@link
 * TwoFactorAwareLoginSuccessHandler} writes it in place of the full session Spring
 * Security's login filter would otherwise have established; {@link
 * AuthController#changePendingPassword} reads it back and clears it once the new password
 * is set.
 */
final class PendingPasswordChangeLogin {

    static final String SESSION_ATTRIBUTE = "pendingPasswordChangeUsername";

    private PendingPasswordChangeLogin() {
    }
}
