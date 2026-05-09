package ch.helvethink.marmot.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps the active DB user in memory for the rotation demo and computes
 * UI metrics (elapsed/remaining) from the configured TTL.
 */
@ApplicationScoped
public class CredentialRotationTracker {

    // User currently observed through CURRENT_USER.
    private String currentUser;
    // Last active user before rotation.
    private String previousUser;
    // First-seen timestamp for the currentUser.
    private Instant currentUserSeenAt;

    /**
     * Updates internal state.
     * If the user changes, it is processed as a credential rotation event.
     */
    public synchronized Snapshot touch(String dbUser, Duration ttl) {
        Instant now = Instant.now();

        if (currentUser == null) {
            currentUser = dbUser;
            currentUserSeenAt = now;
        } else if (!currentUser.equals(dbUser)) {
            // Rotation detected: keep previous user for UI display.
            previousUser = currentUser;
            currentUser = dbUser;
            currentUserSeenAt = now;
        }

        // Elapsed time since currentUser became active.
        Duration elapsed = Duration.between(currentUserSeenAt, now);
        // Remaining time before displayed TTL expiry.
        Duration remaining = ttl.minus(elapsed);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }

        return new Snapshot(currentUser, Optional.ofNullable(previousUser), currentUserSeenAt, elapsed, remaining, ttl);
    }

    public record Snapshot(
            String currentUser,
            Optional<String> previousUser,
            Instant seenAt,
            Duration elapsed,
            Duration remaining,
            Duration ttl) {
    }
}
