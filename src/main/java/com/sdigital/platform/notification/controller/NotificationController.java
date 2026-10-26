package com.sdigital.platform.notification.controller;

import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.notification.dto.CreateNotificationRequest;
import com.sdigital.platform.notification.dto.NotificationResponse;
import com.sdigital.platform.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REST API for in-app notifications, versioned under {@code /api/v1/notifications}.
 *
 * <p>Every operation concerns the authenticated user's own notifications only: the user is
 * resolved from the JWT {@code sub} via {@link CurrentActorResolver}. Cross-user access is
 * prevented by the service layer (ownership check).</p>
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Notifications in-app de l'utilisateur authentifié.")
public class NotificationController {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt");

    private final NotificationService notificationService;
    private final CurrentActorResolver currentActorResolver;

    public NotificationController(NotificationService notificationService,
                                  CurrentActorResolver currentActorResolver) {
        this.notificationService = notificationService;
        this.currentActorResolver = currentActorResolver;
    }

    private static void validateSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new PropertyReferenceException(order.getProperty(), TypeInformation.of(String.class), List.of());
            }
        }
    }

    private UUID currentUserId() {
        return currentActorResolver.resolveCurrentActorUserId();
    }

    @Operation(summary = "Lister les notifications",
            description = "Retourne la liste paginée des notifications de l'utilisateur "
                    + "authentifié, les plus récentes en premier.")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Index de page (base 0, défaut 0).", example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Taille de page (défaut 20, maximum 100).", example = "20"),
            @Parameter(name = "sort", in = ParameterIn.QUERY, description = "Critère de tri `property,asc|desc`. Défaut `createdAt,desc`.", example = "createdAt,desc")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page de notifications"),
            @ApiResponse(responseCode = "400", description = "Paramètre de page, taille ou tri invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Identité non liée ou inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Erreur inattendue",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public Page<NotificationResponse> getAll(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        validateSort(pageable);
        UUID userId = currentUserId();
        return notificationService.getNotifications(userId, pageable);
    }

    @Operation(summary = "Consulter une notification",
            description = "Retourne une notification si elle appartient à l'utilisateur authentifié. "
                    + "Retourne 404 si elle n'existe pas ou appartient à un autre utilisateur.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notification trouvée"),
            @ApiResponse(responseCode = "400", description = "Identifiant malformé",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Identité non liée ou inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Notification introuvable ou non accessible",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public NotificationResponse getById(
            @Parameter(description = "Identifiant de la notification", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        return notificationService.getNotification(id, currentUserId());
    }

    @Operation(summary = "Lister les notifications non lues",
            description = "Retourne la liste paginée des notifications non lues de "
                    + "l'utilisateur authentifié, les plus récentes en premier.")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Index de page (base 0, défaut 0).", example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Taille de page (défaut 20, maximum 100).", example = "20")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page de notifications non lues"),
            @ApiResponse(responseCode = "400", description = "Paramètre de page ou taille invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Identité non liée ou inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/unread")
    public Page<NotificationResponse> getUnread(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        validateSort(pageable);
        UUID userId = currentUserId();
        return notificationService.getUnreadNotifications(userId, pageable);
    }

    @Operation(summary = "Compter les notifications non lues",
            description = "Retourne le nombre de notifications non lues de l'utilisateur authentifié.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nombre de notifications non lues",
                    content = @Content(schema = @Schema(type = "integer"))),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Identité non liée ou inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/unread/count")
    public ResponseEntity<Long> countUnread() {
        UUID userId = currentUserId();
        return ResponseEntity.ok(notificationService.countUnread(userId));
    }

    @Operation(summary = "Marquer une notification comme lue",
            description = "Marque comme lue la notification ciblée si elle appartient à "
                    + "l'utilisateur authentifié. Retourne 404 si elle n'existe pas ou n'appartient pas à l'utilisateur.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Notification marquée comme lue"),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Identité non liée ou inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Notification introuvable ou non accessible",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @Parameter(description = "Identifiant de la notification", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID id) {
        UUID userId = currentUserId();
        notificationService.markAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Marquer toutes les notifications comme lues",
            description = "Marque comme lues toutes les notifications non lues de "
                    + "l'utilisateur authentifié et retourne le nombre de notifications marquées.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nombre de notifications marquées comme lues",
                    content = @Content(schema = @Schema(type = "integer"))),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Identité non liée ou inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/read/all")
    public ResponseEntity<Integer> markAllAsRead() {
        UUID userId = currentUserId();
        int count = notificationService.markAllAsRead(userId);
        return ResponseEntity.ok(count);
    }

    @Operation(summary = "Émettre une notification",
            description = "Crée une notification non lue pour un utilisateur local existant. "
                    + "Opération réservée aux ADMIN. Le destinataire devient propriétaire de la "
                    + "notification ; l'émission est journalisée dans l'audit.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Notification créée"),
            @ApiResponse(responseCode = "400", description = "Corps de requête invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis ou identité non liée/inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Destinataire introuvable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<NotificationResponse> create(
            @Valid @RequestBody CreateNotificationRequest request,
            UriComponentsBuilder uriBuilder) {
        UUID actorId = currentUserId();
        NotificationResponse created = notificationService.createForRecipient(request, actorId);
        URI location = uriBuilder.path("/api/v1/notifications/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }
}
