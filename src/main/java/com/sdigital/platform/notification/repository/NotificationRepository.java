package com.sdigital.platform.notification.repository;

import com.sdigital.platform.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link Notification}.
 *
 * <p>Notifications are created by the application and marked as read by their owner; they
 * are never otherwise modified or deleted through the API.</p>
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Long countByUserIdAndReadFalse(UUID userId);

    /**
     * Returns the unread notifications of a user, used for bulk marking.
     */
    List<Notification> findByUserIdAndReadFalse(UUID userId);
}
