package dev.locklane.engine.push;

/**
 * One browser's Web Push subscription (#860), as the notifier needs it: {@code auth}
 * is the decrypted 16-byte secret in base64url, {@code p256dh} the browser's 65-byte
 * public point in base64url -- both exactly as the browser's own
 * {@code PushSubscription.toJSON()} handed them over.
 */
public record PushSubscriptionRecord(long id, long ownerUserId, String endpoint, String p256dh, String auth) {
}
