package com.sdigital.platform.audit;

import com.sdigital.platform.agency.dto.CreateAgencyRequest;
import com.sdigital.platform.agency.dto.UpdateAgencyRequest;
import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.mapper.AgencyMapper;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.agency.service.AgencyService;
import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.mapper.UserMapper;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.service.UserIdentityService;
import com.sdigital.platform.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transactional integration tests for the persistent audit of local business mutations.
 *
 * <p>They run against a real PostgreSQL 16 container (never H2) and prove the essential
 * guarantee of step 4H.4: the audit entry and the business mutation share the same
 * PostgreSQL transaction, so they are committed together and rolled back together.</p>
 *
 * <p>The test methods run with {@link Propagation#NOT_SUPPORTED} so the service calls run
 * in their own real transaction (which is what production does). A {@link TransactionTemplate}
 * is used to open an explicit transaction when a rollback has to be provoked.</p>
 *
 * <p>Because those transactions are really committed, the fixtures use unique codes and
 * e-mail addresses and every assertion is scoped to the identifier of the row it created.</p>
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
        AgencyMapper.class,
        UserService.class,
        UserMapper.class,
        UserIdentityService.class
})
@Testcontainers
class BusinessAuditTransactionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private AgencyService agencyService;

    @Autowired
    private UserService userService;

    @Autowired
    private AgencyRepository agencyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void businessMutationAndItsAuditEntryAreCommittedTogether() {
        AgencyFixture agency = createAgency();

        AuditLog entry = auditEventsOf(agency.id(), "AGENCY_CREATED").stream()
                .findFirst()
                .orElseThrow();

        assertThat(entry.getEntityType()).isEqualTo("AGENCY");
        assertThat(entry.getEntityId()).isEqualTo(agency.id().toString());
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(agencyRepository.findById(agency.id())).isPresent();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditEntryIsRolledBackWithTheBusinessMutation() {
        AgencyFixture agency = createAgency();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            agencyService.update(agency.id(), new UpdateAgencyRequest(agency.code(), "Agency renamed"));
            throw new IllegalStateException("failure after the mutation and the audit write");
        })).isInstanceOf(IllegalStateException.class);

        // the mutation is rolled back...
        Agency reloaded = agencyRepository.findById(agency.id()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo(agency.name());
        // ...and so is the audit entry written in the same transaction
        assertThat(auditEventsOf(agency.id(), "AGENCY_UPDATED")).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void userUpdateWritesOneUserEventAndOneAssignmentEvent() {
        UserFixture user = createUser();
        UUID userId = user.id();
        AgencyFixture agency = createAgency();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> userService.update(userId,
                new UpdateUserRequest(user.email(), "Alicia", "Doe", agency.id(), null)));

        List<AuditLog> userEvents = auditEventsOf(userId, "USER_UPDATED");
        List<AuditLog> assignmentEvents = auditEventsOf(userId, "USER_ASSIGNMENT_CHANGED");

        assertThat(userEvents).hasSize(1);
        assertThat(assignmentEvents).hasSize(1);
        assertThat(userEvents.get(0).getEntityType()).isEqualTo("USER");
        assertThat(assignmentEvents.get(0).getEntityType()).isEqualTo("USER_ASSIGNMENT");
        assertThat(userEvents.get(0).getEntityId()).isEqualTo(userId.toString());
        assertThat(assignmentEvents.get(0).getEntityId()).isEqualTo(userId.toString());
        assertThat(userEvents.get(0).getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(assignmentEvents.get(0).getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(userEvents.get(0).getDetails())
                .containsEntry("fields", List.of("firstName"));
        assertThat(assignmentEvents.get(0).getDetails())
                .containsEntry("agencyId", agency.id().toString())
                .containsKey("previousAgencyId");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void userUpdateWithoutAssignmentChangeWritesNoAssignmentEvent() {
        UserFixture user = createUser();
        UUID userId = user.id();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> userService.update(userId,
                new UpdateUserRequest(user.email(), "Alicia", "Doe", null, null)));

        assertThat(auditEventsOf(userId, "USER_ASSIGNMENT_CHANGED")).isEmpty();
        assertThat(auditEventsOf(userId, "USER_UPDATED")).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void userUpdateThatChangesNothingWritesNoAuditEvent() {
        UserFixture user = createUser();
        UUID userId = user.id();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> userService.update(userId,
                new UpdateUserRequest(user.email(), "Alice", "Doe", null, null)));

        assertThat(auditEventsOf(userId, "USER_ASSIGNMENT_CHANGED")).isEmpty();
        assertThat(auditEventsOf(userId, "USER_UPDATED")).isEmpty();
        // the row itself is left untouched: not even the modification timestamp moved
        assertThat(userRepository.findById(userId).orElseThrow().getUpdatedAt())
                .isEqualTo(user.createdAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deactivateWritesAStatusTransitionEvent() {
        AgencyFixture agency = createAgency();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> agencyService.deactivate(agency.id()));

        AuditLog entry = auditEventsOf(agency.id(), "AGENCY_DEACTIVATED").stream()
                .findFirst()
                .orElseThrow();

        assertThat(entry.getDetails())
                .containsEntry("previousStatus", "ACTIVE")
                .containsEntry("status", "INACTIVE");
    }

    private List<AuditLog> auditEventsOf(UUID entityId, String action) {
        return auditLogRepository.findAll().stream()
                .filter(log -> action.equals(log.getAction()))
                .filter(log -> entityId.toString().equals(log.getEntityId()))
                .toList();
    }

    private AgencyFixture createAgency() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String code = "AG-" + suffix;
        String name = "Agency " + suffix;
        AtomicReference<UUID> id = new AtomicReference<>();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> id.set(agencyService
                .create(new CreateAgencyRequest(code, name))
                .id()));
        return new AgencyFixture(id.get(), code, name);
    }

    private UserFixture createUser() {
        String email = "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        AtomicReference<UUID> id = new AtomicReference<>();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> id.set(userService
                .create(new CreateUserRequest(email, "Alice", "Doe", null, null))
                .id()));
        return new UserFixture(id.get(), email, userRepository.findById(id.get()).orElseThrow().getCreatedAt());
    }

    private record AgencyFixture(UUID id, String code, String name) {
    }

    private record UserFixture(UUID id, String email, Instant createdAt) {
    }
}
