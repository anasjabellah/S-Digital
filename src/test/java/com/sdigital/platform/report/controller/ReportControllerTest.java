package com.sdigital.platform.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sdigital.platform.report.dto.ReportFormat;
import com.sdigital.platform.report.dto.ReportType;
import com.sdigital.platform.report.service.ReportService;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @Test
    void usersPdfReportIsDownloadable() throws Exception {
        when(reportService.generate(ReportType.USERS, ReportFormat.PDF))
                .thenReturn("%PDF-1.4 fake".getBytes());

        mockMvc.perform(get("/api/v1/reports")
                .param("type", "users")
                .param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().exists("Content-Disposition"));
    }

    @Test
    void agenciesXlsxReportIsDownloadable() throws Exception {
        when(reportService.generate(ReportType.AGENCIES, ReportFormat.XLSX))
                .thenReturn("xlsx fake".getBytes());

        mockMvc.perform(get("/api/v1/reports")
                .param("type", "agencies")
                .param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().exists("Content-Disposition"));
    }

    @Test
    void defaultFormatIsPdf() throws Exception {
        when(reportService.generate(ReportType.FUNCTIONS, ReportFormat.PDF))
                .thenReturn("%PDF fake".getBytes());

        mockMvc.perform(get("/api/v1/reports")
                .param("type", "functions"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void unknownReportTypeIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/reports")
                .param("type", "invoices"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownReportFormatIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/reports")
                .param("type", "users")
                .param("format", "csv"))
                .andExpect(status().isBadRequest());
    }
}
