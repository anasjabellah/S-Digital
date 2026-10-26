package com.sdigital.platform.notification.service;

import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.notification.dto.NotificationResponse;
import com.sdigital.platform.notification.entity.Notification;
import com.sdigital.platform.notification.exception.NotificationNotFoundException;
import com.sdigital.platform.notification.mapper.NotificationMapper;
import com.sdigital.platform.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for in-app notifications.
 *
 * <p>Notifications belong to a single local user. Every read path is filtered by
 * {@code user_id} at the database level and every write path enforces ownership, so a
 * user can never read or mark-as-read another user's notification (no IDOR).</p>
 *
 * <p>The current user is always resolved by the caller through the security context
 * (see {@code CurrentActorResolver}): this service never looks up a user by email and
 * never provisions one implicitly.</p>
 */
@Service
public class NotificationService {

    private static final String AUDIT_ENTITY = "NOTIFICATION";
    private static final String ACTION_NOTIFICATION_CREATED = "NOTIFICATION_CREATED";

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final AuditLogService auditLogService;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationMapper notificationMapper,
                               AuditLogService auditLogService) {
        this.notificationRepository = notificationRepository;
        this.notificationMapper = notificationMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * Returns the paginated list of all notifications of a user, newest first.
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(UUID userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(notificationMapper::toResponse);
    }

    /**
     * Returns the paginated list of unread notifications of a user, newest first.
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getUnreadNotifications(UUID userId, Pageable pageable) {
        return notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable)
                .map(notificationMapper::toResponse);
    }

    /**
     * Returns the number of unread notifications of a user.
     */
    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    /**
     * Marks a single notification as read.
     *
     * <p>The notification must exist and belong to the given user; otherwise no
     * information about its existence is leaked (404 either way).</p>
     */
    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        if (!notification.getUserId().equals(userId)) {
            throw new NotificationNotFoundException(notificationId);
        }
        notification.markRead();
    }

    /**
     * Marks all unread notifications of a user as read. Returns the number of
     * notifications that were actually marked.
     */
    @Transactional
    public int markAllAsRead(UUID userId) {
        List<Notification> unread = notificationRepository.findByUserIdAndReadFalse(userId);
        Instant now = Instant.now();
        int count = 0;
        for (Notification notification : unread) {
            notification.markRead();
            count++;
        }
        return count;
    }

    /**
     * Creates a notification for a user and records an audit entry.
     *
     * <p>Intended for use by business modules when a notification-worthy event occurs.
     * The creation is audited as {@code NOTIFICATION_CREATED}.</p>
     */
    @Transactional
    public NotificationResponse createNotification(UUID userId, String title, String message,
                                                   String type, String link,
                                                   Map<String, Object> metadata,
                                                   UUID actorUserId) {
        Notification notification = new Notification(userId, title, message, type, link, metadata);
        notification = notificationRepository.save(notification);
        auditLogService.record(actorUserId, ACTION_NOTIFICATION_CREATED, AUDIT_ENTITY,
                userId.toString(), AuditOutcome.SUCCESS,
                Map.of("notificationId", notification.getId().toString(), "type", type));
        return notificationMapper.toResponse(notification);
    }
}
