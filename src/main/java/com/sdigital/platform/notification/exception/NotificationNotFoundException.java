package com.sdigital.platform.notification.exception;

import java.util.UUID;

/**
 * Thrown when a notification is not found or does not belong to the requesting user.
 *
 * <p>Both cases map to 404 so that no information about the existence or ownership of a
 * notification is leaked to another user.</p>
 */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(UUID notificationId) {
        super("Notification not found: " + notificationId);
    }
}
