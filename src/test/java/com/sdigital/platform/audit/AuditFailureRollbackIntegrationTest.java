package com.sdigital.platform.audit;

import com.sdigital.platform.agency.dto.UpdateAgencyRequest;
import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.mapper.AgencyMapper;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.agency.service.AgencyService;
import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Integration test proving the other side of the transaction contract: when the audit write
 * fails, the business mutation is rolled back as well.
 *
 * <p>The audit repository is stubbed to fail, which is the closest reproducible equivalent of
 * the audit table being unavailable. The business row is written directly (bypassing the
 * audited service) so the fixture itself is not affected by the failure.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AuditLogService.class,
        CurrentActorResolver.class,
        AgencyService.class,
        AgencyMapper.class
})
@Testcontainers
class AuditFailureRollbackIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private AgencyService agencyService;

    @Autowired
    private AgencyRepository agencyRepository;

    @MockitoBean
    private AuditLogRepository auditLogRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditWriteFailureRollsBackTheBusinessMutation() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "AG-" + suffix;
        String name = "Agency " + suffix;
        Agency agency = agencyRepository.save(new Agency(code, name));
        UUID agencyId = agency.getId();

        given(auditLogRepository.save(any(AuditLog.class)))
                .willThrow(new DataAccessResourceFailureException("audit table unavailable"));

        assertThatThrownBy(() -> agencyService.update(agencyId, new UpdateAgencyRequest(code, "Renamed agency")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        Agency reloaded = agencyRepository.findById(agencyId).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo(name);
    }
}
