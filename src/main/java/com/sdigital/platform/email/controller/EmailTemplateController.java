package com.sdigital.platform.email.controller;

import com.sdigital.platform.email.dto.CreateEmailTemplateRequest;
import com.sdigital.platform.email.dto.EmailTemplateResponse;
import com.sdigital.platform.email.dto.UpdateEmailTemplateRequest;
import com.sdigital.platform.email.entity.EmailTemplate;
import com.sdigital.platform.email.entity.EmailTemplateStatus;
import com.sdigital.platform.email.service.EmailTemplateService;
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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REST API for email templates, versioned under {@code /api/v1/email-templates}.
 *
 * <p>The controller contains no business logic: it delegates to {@link EmailTemplateService}
 * and only handles HTTP concerns. Templates are stored content models; this API never
 * sends mail.</p>
 */
@RestController
@RequestMapping("/api/v1/email-templates")
@Tag(name = "Modèles d'emails", description = "Gestion des modèles d'emails (création, consultation, mise à jour, activation/désactivation). Aucun envoi SMTP.")
public class EmailTemplateController {

    private static final String ID_EXAMPLE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "id", "code", "name", "locale", "status", "createdAt", "updatedAt");

    private final EmailTemplateService emailTemplateService;

    public EmailTemplateController(EmailTemplateService emailTemplateService) {
        this.emailTemplateService = emailTemplateService;
    }

    private static void validateSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new PropertyReferenceException(
                        order.getProperty(), TypeInformation.of(EmailTemplate.class), List.of());
            }
        }
    }

    @Operation(summary = "Créer un modèle d'email",
            description = "Crée un nouveau modèle avec le statut ACTIVE. Les placeholders du sujet "
                    + "et du corps doivent utiliser `{{variableName}}` et être déclarés dans `variables`. "
                    + "Aucun email n'est envoyé.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Email template created"),
            @ApiResponse(responseCode = "400", description = "Invalid or unreadable request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Email template code already in use",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<EmailTemplateResponse> create(
            @Valid @RequestBody CreateEmailTemplateRequest request,
            UriComponentsBuilder uriBuilder) {
        EmailTemplateResponse created = emailTemplateService.create(request);
        URI location = uriBuilder.path("/api/v1/email-templates/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Consulter un modèle d'email",
            description = "Retourne un modèle d'email par son UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email template found"),
            @ApiResponse(responseCode = "400", description = "Malformed email template id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Email template not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public EmailTemplateResponse getById(
            @Parameter(description = "UUID of the email template", example = ID_EXAMPLE)
            @PathVariable UUID id) {
        return emailTemplateService.getById(id);
    }

    @Operation(summary = "Lister les modèles d'emails",
            description = "Retourne une liste paginée de modèles. `search` est une correspondance "
                    + "partielle insensible à la casse sur le code et le nom ; `status` et `locale` "
                    + "sont des correspondances exactes.")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Zero-based page index.", example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Page size (default 20, maximum 100).", example = "20"),
            @Parameter(name = "sort", in = ParameterIn.QUERY,
                    description = "Sort criteria as `property,asc|desc`; repeatable. Default `createdAt,desc`.",
                    example = "code,asc"),
            @Parameter(name = "search", in = ParameterIn.QUERY,
                    description = "Case-insensitive partial match on code and name.", example = "welcome"),
            @Parameter(name = "status", in = ParameterIn.QUERY,
                    description = "Exact lifecycle status filter: ACTIVE or INACTIVE.", example = "ACTIVE"),
            @Parameter(name = "locale", in = ParameterIn.QUERY,
                    description = "Exact locale filter (e.g. fr).", example = "fr")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of email templates"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, sort or filter parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public Page<EmailTemplateResponse> getAll(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) EmailTemplateStatus status,
            @RequestParam(required = false) String locale) {
        validateSort(pageable);
        return emailTemplateService.getAll(search, status, locale, pageable);
    }

    @Operation(summary = "Mettre à jour un modèle d'email",
            description = "Remplace les champs modifiables. Le statut n'est pas modifié par cette opération.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email template updated"),
            @ApiResponse(responseCode = "400", description = "Malformed id or invalid request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Email template not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Email template code already in use",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/{id}")
    public EmailTemplateResponse update(
            @Parameter(description = "UUID of the email template", example = ID_EXAMPLE)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEmailTemplateRequest request) {
        return emailTemplateService.update(id, request);
    }

    @Operation(summary = "Désactiver un modèle d'email",
            description = "Désactivation logique : le statut passe à INACTIVE et la ligne est conservée.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Email template deactivated"),
            @ApiResponse(responseCode = "400", description = "Malformed email template id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Email template not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(
            @Parameter(description = "UUID of the email template", example = ID_EXAMPLE)
            @PathVariable UUID id) {
        emailTemplateService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Réactiver un modèle d'email",
            description = "Réactive un modèle désactivé : le statut redevient ACTIVE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email template activated"),
            @ApiResponse(responseCode = "400", description = "Malformed email template id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Email template not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{id}/activate")
    public EmailTemplateResponse activate(
            @Parameter(description = "UUID of the email template", example = ID_EXAMPLE)
            @PathVariable UUID id) {
        return emailTemplateService.activate(id);
    }
}
