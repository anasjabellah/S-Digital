package com.sdigital.platform.report.service;

import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.report.dto.ReportFormat;
import com.sdigital.platform.report.dto.ReportType;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates PDF and Excel reports over the existing domain data.
 *
 * <p>Reports only re-expose data already visible through the existing list endpoints
 * (users, agencies, functions). No token, secret, credential or additional personal
 * data is ever included.</p>
 */
@Service
public class ReportService {

    private final UserRepository userRepository;
    private final AgencyRepository agencyRepository;
    private final FunctionRepository functionRepository;

    public ReportService(UserRepository userRepository,
                         AgencyRepository agencyRepository,
                         FunctionRepository functionRepository) {
        this.userRepository = userRepository;
        this.agencyRepository = agencyRepository;
        this.functionRepository = functionRepository;
    }

    /**
     * Generates the report for the given type and format.
     *
     * @param type   report type (users, agencies, functions)
     * @param format output format (pdf or xlsx)
     * @return the report bytes, ready to be served as a download
     */
    @Transactional(readOnly = true)
    public byte[] generate(ReportType type, ReportFormat format) {
        ReportData data = collectData(type);
        return switch (format) {
            case PDF -> renderPdf(data);
            case XLSX -> renderXlsx(data);
        };
    }

    private ReportData collectData(ReportType type) {
        String title;
        String[] headers;
        List<String[]> rows;
        int total;
        switch (type) {
            case USERS -> {
                List<User> users = userRepository.findAll();
                headers = new String[]{"ID", "Email", "Prénom", "Nom", "Statut", "Agence", "Fonction", "Créé le"};
                rows = new ArrayList<>(users.size());
                for (User user : users) {
                    rows.add(new String[]{
                            user.getId() == null ? "-" : user.getId().toString(),
                            user.getEmail(),
                            user.getFirstName(),
                            user.getLastName(),
                            user.getStatus().name(),
                            user.getAgency() == null ? "-" : user.getAgency().getCode(),
                            user.getFunction() == null ? "-" : user.getFunction().getCode(),
                            user.getCreatedAt() == null ? "-" : user.getCreatedAt().toString()
                    });
                }
                title = "Rapport des utilisateurs";
                total = users.size();
            }
            case AGENCIES -> {
                List<Agency> agencies = agencyRepository.findAll();
                headers = new String[]{"ID", "Code", "Nom", "Statut", "Créé le"};
                rows = new ArrayList<>(agencies.size());
                for (Agency agency : agencies) {
                    rows.add(new String[]{
                            agency.getId().toString(),
                            agency.getCode(),
                            agency.getName(),
                            agency.getStatus().name(),
                            agency.getCreatedAt() == null ? "-" : agency.getCreatedAt().toString()
                    });
                }
                title = "Rapport des agences";
                total = agencies.size();
            }
            case FUNCTIONS -> {
                List<Function> functions = functionRepository.findAll();
                headers = new String[]{"ID", "Code", "Nom", "Statut", "Créé le"};
                rows = new ArrayList<>(functions.size());
                for (Function function : functions) {
                    rows.add(new String[]{
                            function.getId().toString(),
                            function.getCode(),
                            function.getName(),
                            function.getStatus().name(),
                            function.getCreatedAt() == null ? "-" : function.getCreatedAt().toString()
                    });
                }
                title = "Rapport des fonctions";
                total = functions.size();
            }
            default -> throw new IllegalArgumentException("Unsupported report type: " + type);
        }
        return new ReportData(title, headers, rows, total);
    }

    private byte[] renderPdf(ReportData data) {
        try (PDDocument document = new PDDocument()) {
            float margin = 40f;
            float pageWidth = PDRectangle.A4.getWidth();
            float pageHeight = PDRectangle.A4.getHeight();
            float[] widths = columnWidths(data.headers().length, pageWidth - 2 * margin);

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(document, page);
            try {
                float y = pageHeight - margin;
                // Title
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16);
                cs.newLineAtOffset(margin, y);
                cs.showText(data.title());
                cs.endText();
                y -= 24;
                // Meta: generated at + total
                String meta = "Généré le " + Instant.now().atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        + " - " + data.rowCount() + " entrée(s)";
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 10);
                cs.newLineAtOffset(margin, y);
                cs.showText(meta);
                cs.endText();
                y -= 28;

                y = drawPdfHeader(cs, margin, y, data, widths);
                for (String[] row : data.rows()) {
                    if (y < margin + 20) {
                        cs.close();
                        PDPage nextPage = new PDPage(PDRectangle.A4);
                        document.addPage(nextPage);
                        cs = new PDPageContentStream(document, nextPage);
                        y = pageHeight - margin;
                        y = drawPdfHeader(cs, margin, y, data, widths);
                    }
                    y = drawPdfRow(cs, y, margin, widths, row);
                }
            } finally {
                cs.close();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate PDF report", ex);
        }
    }

    private float drawPdfHeader(PDPageContentStream cs, float margin, float y, ReportData data, float[] widths)
            throws IOException {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_BOLD, 9);
        cs.newLineAtOffset(margin, y);
        cs.showText(truncate(data.headers()[0], Math.max(1, (int) widths[0] / 2)));
        for (int i = 1; i < data.headers().length; i++) {
            cs.newLineAtOffset(widths[i - 1], 0);
            cs.showText(truncate(data.headers()[i], Math.max(1, (int) widths[i] / 2)));
        }
        cs.endText();
        return y - 16;
    }

    private float drawPdfRow(PDPageContentStream cs, float y, float margin, float[] widths, String[] row)
            throws IOException {
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, 8);
        cs.newLineAtOffset(margin, y);
        cs.showText(truncate(row[0], Math.max(1, (int) widths[0] / 2)));
        for (int i = 1; i < row.length; i++) {
            cs.newLineAtOffset(widths[i - 1], 0);
            cs.showText(truncate(row[i], Math.max(1, (int) widths[i] / 2)));
        }
        cs.endText();
        return y - 12;
    }

    private float[] columnWidths(int columnCount, float availableWidth) {
        float[] widths = new float[columnCount];
        float equal = availableWidth / columnCount;
        for (int i = 0; i < columnCount; i++) {
            widths[i] = equal;
        }
        return widths;
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private byte[] renderXlsx(ReportData data) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Rapport");
            XSSFRow headerRow = sheet.createRow(0);
            for (int i = 0; i < data.headers().length; i++) {
                headerRow.createCell(i).setCellValue(data.headers()[i]);
            }
            int rowIndex = 1;
            for (String[] row : data.rows()) {
                XSSFRow dataRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < row.length; i++) {
                    dataRow.createCell(i).setCellValue(row[i]);
                }
            }
            for (int i = 0; i < data.headers().length; i++) {
                sheet.autoSizeColumn(i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Excel report", ex);
        }
    }

    /**
     * Collected report data, ready to render.
     */
    private record ReportData(String title, String[] headers, List<String[]> rows, int rowCount) {
    }
}
