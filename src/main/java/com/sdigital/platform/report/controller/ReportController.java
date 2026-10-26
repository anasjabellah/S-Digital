package com.sdigital.platform.report.controller;

import com.sdigital.platform.report.dto.ReportFormat;
import com.sdigital.platform.report.dto.ReportType;
import com.sdigital.platform.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * REST API for report generation, versioned under {@code /api/v1/reports}.
 *
 * <p>Reports are read-only exports of existing domain data (users, agencies,
 * functions). Access is restricted to ADMIN because a report contains the full
 * listing of a domain resource.</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Rapports", description = "Génération et téléchargement de rapports (PDF et Excel).")
public class ReportController {

    private static final DateTimeFormatter FILENAME_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(summary = "Générer un rapport",
            description = "Génère un rapport au format PDF ou Excel sur le type de données "
                    + "demandé (utilisateurs, agences ou fonctions) et le retourne en téléchargement. "
                    + "Le fichier contient un titre, une date de génération, le nombre d'entrées et "
                    + "le détail des données du type sélectionné.")
    @Parameters({
            @Parameter(name = "type", in = ParameterIn.QUERY, description = "Type de rapport : users, agencies ou functions.", required = true, example = "users"),
            @Parameter(name = "format", in = ParameterIn.QUERY, description = "Format de sortie : pdf (défaut) ou xlsx.", example = "pdf")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rapport généré",
                    content = @Content(mediaType = "application/pdf",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "Type ou format de rapport invalide",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Jeton manquant ou invalide",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Accès réservé aux ADMIN",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Erreur inattendue lors de la génération",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @GetMapping
    public ResponseEntity<byte[]> getReport(
            @RequestParam String type,
            @RequestParam(required = false, defaultValue = "pdf") String format) {
        ReportType reportType = ReportType.fromCode(type);
        ReportFormat reportFormat = ReportFormat.fromCode(format);
        byte[] content = reportService.generate(reportType, reportFormat);
        String fileName = reportType.getCode() + "-" + FILENAME_DATE.format(Instant.now()) + "." + reportFormat.getCode();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(reportFormat.getMediaType()))
                .body(content);
    }
}
