package com.sdigital.platform.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.notification.dto.NotificationResponse;
import com.sdigital.platform.notification.entity.Notification;
import com.sdigital.platform.notification.exception.NotificationNotFoundException;
import com.sdigital.platform.notification.mapper.NotificationMapper;
import com.sdigital.platform.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        NotificationService.class,
        CurrentActorResolver.class,
        NotificationMapper.class,
        AuditLogService.class
})
class NotificationServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("created notification is persisted and audited")
    void createdNotificationIsPersistedAndAudited() {
        UUID userId = createUserId();
        UUID actorId = UUID.randomUUID();

        NotificationResponse created = new TransactionTemplate(transactionManager)
                .execute(status -> notificationService.createNotification(
                        userId, "Titre", "Message", "INFO", "/users/1", Map.of(), actorId));

        assertThat(created.id()).isNotNull();
        assertThat(created.userId()).isEqualTo(userId);
        assertThat(created.read()).isFalse();

        Notification stored = notificationRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Titre");
        assertThat(stored.isRead()).isFalse();

        assertThat(auditEventsOf("NOTIFICATION_CREATED")).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("getNotifications returns all notifications of the user, newest first")
    void getNotificationsReturnsAllOfUser() {
        UUID userId = createUserId();
        new TransactionTemplate(transactionManager).execute(status -> {
            notificationService.createNotification(userId, "Plus ancienne", "M", "INFO", null, null, UUID.randomUUID());
            notificationService.createNotification(userId, "Plus récente", "M", "INFO", null, null, UUID.randomUUID());
            return null;
        });

        List<NotificationResponse> page = notificationService
                .getNotifications(userId, PageRequest.of(0, 20)).getContent();

        assertThat(page).hasSize(2);
        assertThat(page.get(0).title()).isEqualTo("Plus récente");
        assertThat(page.get(1).title()).isEqualTo("Plus ancienne");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("getUnreadNotifications returns only unread notifications")
    void getUnreadNotificationsReturnsOnlyUnread() {
        UUID userId = createUserId();
        UUID firstId = new TransactionTemplate(transactionManager).execute(status ->
                notificationService.createNotification(userId, "Lue", "M", "INFO", null, null, UUID.randomUUID()).id());
        new TransactionTemplate(transactionManager).execute(status ->
                notificationService.createNotification(userId, "Non lue", "M", "INFO", null, null, UUID.randomUUID()));

        notificationService.markAsRead(firstId, userId);

        List<NotificationResponse> unread = notificationService
                .getUnreadNotifications(userId, PageRequest.of(0, 20)).getContent();

        assertThat(unread).hasSize(1);
        assertThat(unread.get(0).title()).isEqualTo("Non lue");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("countUnread returns the number of unread notifications")
    void countUnreadReturnsNumber() {
        UUID userId = createUserId();
        new TransactionTemplate(transactionManager).execute(status -> {
            notificationService.createNotification(userId, "A", "M", "INFO", null, null, UUID.randomUUID());
            notificationService.createNotification(userId, "B", "M", "INFO", null, null, UUID.randomUUID());
            notificationService.createNotification(userId, "C", "M", "INFO", null, null, UUID.randomUUID());
            return null;
        });
        notificationService.markAllAsRead(userId);

        assertThat(notificationService.countUnread(userId)).isZero();

        new TransactionTemplate(transactionManager).execute(status -> {
            notificationService.createNotification(userId, "D", "M", "INFO", null, null, UUID.randomUUID());
            return null;
        });
        assertThat(notificationService.countUnread(userId)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("markAsRead marks the notification as read")
    void markAsReadMarksNotification() {
        UUID userId = createUserId();
        UUID notificationId = new TransactionTemplate(transactionManager)
                .execute(status -> notificationService.createNotification(
                        userId, "À lire", "M", "INFO", null, null, UUID.randomUUID()).id());

        notificationService.markAsRead(notificationId, userId);

        Notification stored = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(stored.isRead()).isTrue();
        assertThat(stored.getReadAt()).isNotNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("markAsRead of another user's notification is not found")
    void markAsReadOfAnotherUserIsNotFound() {
        UUID ownerId = createUserId();
        UUID otherId = createUserId();
        UUID notificationId = new TransactionTemplate(transactionManager)
                .execute(status -> notificationService.createNotification(
                        ownerId, "À lire", "M", "INFO", null, null, UUID.randomUUID()).id());

        assertThatThrownBy(() -> notificationService.markAsRead(notificationId, otherId))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("markAllAsRead marks all unread notifications and returns the count")
    void markAllAsReadMarksAll() {
        UUID userId = createUserId();
        new TransactionTemplate(transactionManager).execute(status -> {
            notificationService.createNotification(userId, "A", "M", "INFO", null, null, UUID.randomUUID());
            notificationService.createNotification(userId, "B", "M", "INFO", null, null, UUID.randomUUID());
            notificationService.createNotification(userId, "C", "M", "INFO", null, null, UUID.randomUUID());
            return null;
        });

        int marked = new TransactionTemplate(transactionManager)
                .execute(status -> notificationService.markAllAsRead(userId));

        assertThat(marked).isEqualTo(3);
        assertThat(notificationService.countUnread(userId)).isZero();
    }

    private List<AuditLog> auditEventsOf(String action) {
        return auditLogRepository.findAll().stream()
                .filter(log -> action.equals(log.getAction()))
                .toList();
    }

    private UUID createUserId() {
        return UUID.randomUUID();
    }
}
