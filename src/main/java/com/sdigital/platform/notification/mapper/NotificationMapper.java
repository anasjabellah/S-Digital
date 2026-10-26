package com.sdigital.platform.notification.mapper;

import com.sdigital.platform.notification.dto.NotificationResponse;
import com.sdigital.platform.notification.entity.Notification;
import org.springframework.stereotype.Component;

/**
 * Manual mapper between the {@link Notification} entity and its read model.
 */
@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getType(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getReadAt(),
                notification.getLink(),
                notification.getMetadata()
        );
    }
}
