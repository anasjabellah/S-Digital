package com.sdigital.platform.report.dto;

/**
 * Supported report types.
 *
 * <p>Each type exports the corresponding domain data already exposed by the existing
 * list endpoints (users, agencies, functions). No additional personal data is exposed.</p>
 */
public enum ReportType {

    USERS("users", "Utilisateurs"),
    AGENCIES("agencies", "Agences"),
    FUNCTIONS("functions", "Fonctions");

    private final String code;
    private final String label;

    ReportType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Parses a report type from its code, case-insensitively.
     *
     * @throws IllegalArgumentException when the code is unknown or blank
     */
    public static ReportType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Report type must not be blank");
        }
        for (ReportType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown report type: '" + code + "'");
    }
}
