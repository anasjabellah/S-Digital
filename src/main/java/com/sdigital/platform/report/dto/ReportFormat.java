package com.sdigital.platform.report.dto;

/**
 * Supported report output formats.
 */
public enum ReportFormat {

    PDF("pdf", "application/pdf", "report.pdf"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "report.xlsx");

    private final String code;
    private final String mediaType;
    private final String defaultFileName;

    ReportFormat(String code, String mediaType, String defaultFileName) {
        this.code = code;
        this.mediaType = mediaType;
        this.defaultFileName = defaultFileName;
    }

    public String getCode() {
        return code;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getDefaultFileName() {
        return defaultFileName;
    }

    public static ReportFormat fromCode(String code) {
        if (code == null || code.isBlank()) {
            return PDF;
        }
        for (ReportFormat format : values()) {
            if (format.code.equalsIgnoreCase(code.trim())) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unknown report format: '" + code + "'");
    }
}
