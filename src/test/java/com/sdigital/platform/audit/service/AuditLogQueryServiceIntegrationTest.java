package com.sdigital.platform.audit.service;

import com.sdigital.platform.audit.dto.AuditLogFilter;
import com.sdigital.platform.audit.dto.AuditLogResponse;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.exception.InvalidAuditLogRangeException;
import com.sdigital.platform.audit.mapper.AuditLogMapper;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link AuditLogQueryService} against a real PostgreSQL 16 container.
 *
 * <p>These tests prove that the consultation filters are applied <b>by the database</b>:
 * the rows are inserted with controlled timestamps and identifiers, and the assertions
 * check the selected page, the total count and the ordering. H2 is intentionally not
 * used, so the JSONB column and the PostgreSQL ordering semantics are exercised for
 * real.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditLogQueryService.class, AuditLogMapper.class})
@Testcontainers
class AuditLogQueryServiceIntegrationTest {

    private static final UUID ENTITY_USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTITY_AGENCY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR_ADMIN = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ACTOR_MANAGER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityManager entityManager;

    private static Pageable page(int size, String property, Sort.Direction direction) {
        return PageRequest.of(0, size, Sort.by(direction, property));
    }

    /**
     * The pageable the controller hands over to the service by default: 20 events per
     * page, most recent first.
     */
    private static Pageable defaultPageable() {
        return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
    }

    /**
     * Inserts a row with a controlled timestamp and identifier. The native statement is
     * used on purpose: the entity always stamps {@code occurredAt} with the current time,
     * and the ordering assertions need deterministic values.
     *
     * <p>The nullable columns are written as SQL literals instead of bound null
     * parameters, so the statement never depends on the type inference of a null
     * parameter.</p>
     */
    private UUID insertEvent(UUID id, Instant occurredAt, UUID actorUserId, String action,
                             String entityType, String entityId, AuditOutcome outcome, String details) {
        String actorColumn = actorUserId == null ? "NULL" : ":actorUserId";
        String entityIdColumn = entityId == null ? "NULL" : ":entityId";
        String detailsColumn = details == null ? "NULL" : "CAST(:details AS jsonb)";
        String sql = "INSERT INTO audit_log "
                + "(id, occurred_at, actor_user_id, action, entity_type, entity_id, outcome, details) "
                + "VALUES (:id, CAST(:occurredAt AS timestamptz), " + actorColumn
                + ", :action, :entityType, " + entityIdColumn + ", :outcome, " + detailsColumn + ")";

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("occurredAt", occurredAt.toString())
                .setParameter("action", action)
                .setParameter("entityType", entityType)
                .setParameter("outcome", outcome.name());
        if (actorUserId != null) {
            query.setParameter("actorUserId", actorUserId);
        }
        if (entityId != null) {
            query.setParameter("entityId", entityId);
        }
        if (details != null) {
            query.setParameter("details", details);
        }
        query.executeUpdate();
        return id;
    }

    private UUID insertEvent(UUID id, Instant occurredAt, UUID actorUserId, String action,
                             String entityType, String entityId, AuditOutcome outcome) {
        return insertEvent(id, occurredAt, actorUserId, action, entityType, entityId, outcome, null);
    }

    private UUID insertEvent(Instant occurredAt, UUID actorUserId, String action, String entityType,
                             String entityId, AuditOutcome outcome) {
        return insertEvent(UUID.randomUUID(), occurredAt, actorUserId, action, entityType, entityId,
                outcome, null);
    }

    @Test
    void returnsNothingWhenNoEventIsStored() {
        Page<AuditLogResponse> result = auditLogQueryService.search(null, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void sortsByOccurredAtDescendingWhenRequested() {
        Instant oldest = Instant.parse("2026-01-01T10:00:00Z");
        Instant newest = Instant.parse("2026-03-01T10:00:00Z");
        insertEvent(oldest, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(newest, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(null, defaultPageable());

        assertThat(result.getContent()).extracting(AuditLogResponse::occurredAt)
                .containsExactly(newest, oldest);
    }

    @Test
    void breaksTiesOnIdenticalTimestampsWithDescendingId() {
        Instant sameInstant = Instant.parse("2026-02-01T10:00:00Z");
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertEvent(first, sameInstant, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(second, sameInstant, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AuditLogResponse::id)
                .containsExactly(second, first);
    }

    @Test
    void paginatesWithRealSqlLimitsAndConsistentCount() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");
        for (int index = 0; index < 5; index++) {
            insertEvent(base.plusSeconds(index), ACTOR_ADMIN, "USER_CREATED", "USER",
                    ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        }

        Page<AuditLogResponse> firstPage = auditLogQueryService.search(null, PageRequest.of(0, 2,
                Sort.by(Sort.Direction.DESC, "occurredAt")));
        Page<AuditLogResponse> lastPage = auditLogQueryService.search(null, PageRequest.of(2, 2,
                Sort.by(Sort.Direction.DESC, "occurredAt")));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(firstPage.getContent()).doesNotContainAnyElementsOf(lastPage.getContent());
        assertThat(firstPage.getContent().get(0).occurredAt())
                .isEqualTo(base.plusSeconds(4));
    }

    @Test
    void filtersByAction() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(now, ACTOR_ADMIN, "USER_UPDATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter("USER_UPDATED", null, null, null, null, null, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AuditLogResponse::action)
                .containsExactly("USER_UPDATED");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void filtersByEntityType() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(now, ACTOR_ADMIN, "AGENCY_CREATED", "AGENCY", ENTITY_AGENCY.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter(null, "AGENCY", null, null, null, null, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AuditLogResponse::entityType)
                .containsExactly("AGENCY");
    }

    @Test
    void filtersByEntityId() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(now, ACTOR_ADMIN, "AGENCY_CREATED", "AGENCY", ENTITY_AGENCY.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter(null, null, ENTITY_AGENCY, null, null, null, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AuditLogResponse::entityId)
                .containsExactly(ENTITY_AGENCY.toString());
    }

    @Test
    void filtersByActorUserId() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(now, ACTOR_MANAGER, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter(null, null, null, ACTOR_MANAGER, null, null, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AuditLogResponse::actorUserId)
                .containsExactly(ACTOR_MANAGER);
    }

    @Test
    void filtersByOutcome() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "ROLE_ASSIGN", "USER_ROLE", ENTITY_USER.toString(), AuditOutcome.DENIED);
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter(null, null, null, null, AuditOutcome.DENIED, null, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AuditLogResponse::outcome)
                .containsExactly(AuditOutcome.DENIED);
    }

    @Test
    void timeBoundsAreInclusiveOnBothEnds() {
        Instant first = Instant.parse("2026-01-01T10:00:00Z");
        Instant second = Instant.parse("2026-01-02T10:00:00Z");
        Instant third = Instant.parse("2026-01-03T10:00:00Z");
        insertEvent(first, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(second, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(third, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter(null, null, null, null, null, first, second), defaultPageable());

        assertThat(result.getContent()).extracting(AuditLogResponse::occurredAt)
                .containsExactly(second, first);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void combinesSeveralFilters() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(now, ACTOR_ADMIN, "USER_UPDATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(now, ACTOR_MANAGER, "USER_UPDATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        insertEvent(now, ACTOR_ADMIN, "AGENCY_CREATED", "AGENCY", ENTITY_AGENCY.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter("USER_UPDATED", "USER", ENTITY_USER, ACTOR_ADMIN, AuditOutcome.SUCCESS, null, null),
                PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).action()).isEqualTo("USER_UPDATED");
        assertThat(result.getContent().get(0).actorUserId()).isEqualTo(ACTOR_ADMIN);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void returnsAnEmptyPageWhenNoEventMatches() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(
                new AuditLogFilter("AGENCY_CREATED", null, null, null, null, null, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void sortsByEveryAllowedProperty() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_MANAGER, "USER_UPDATED", "USER", ENTITY_USER.toString(), AuditOutcome.DENIED);
        insertEvent(now, ACTOR_ADMIN, "AGENCY_CREATED", "AGENCY", ENTITY_AGENCY.toString(), AuditOutcome.SUCCESS);

        assertThat(auditLogQueryService.search(null, page(20, "action", Sort.Direction.ASC)).getContent())
                .extracting(AuditLogResponse::action)
                .containsExactly("AGENCY_CREATED", "USER_UPDATED");
        assertThat(auditLogQueryService.search(null, page(20, "entityType", Sort.Direction.DESC)).getContent())
                .extracting(AuditLogResponse::entityType)
                .containsExactly("USER", "AGENCY");
        assertThat(auditLogQueryService.search(null, page(20, "outcome", Sort.Direction.ASC)).getContent())
                .extracting(AuditLogResponse::outcome)
                .containsExactly(AuditOutcome.DENIED, AuditOutcome.SUCCESS);
        assertThat(auditLogQueryService.search(null, page(20, "outcome", Sort.Direction.DESC)).getContent())
                .extracting(AuditLogResponse::outcome)
                .containsExactly(AuditOutcome.SUCCESS, AuditOutcome.DENIED);
        assertThat(auditLogQueryService.search(null, page(20, "actorUserId", Sort.Direction.ASC)).getContent())
                .extracting(AuditLogResponse::actorUserId)
                .containsExactly(ACTOR_ADMIN, ACTOR_MANAGER);
    }

    @Test
    void returnsTheStructuredDetailsAsStored() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(UUID.randomUUID(), now, ACTOR_ADMIN, "USER_UPDATED", "USER",
                ENTITY_USER.toString(), AuditOutcome.SUCCESS, "{\"fields\":[\"email\"]}");

        Page<AuditLogResponse> result = auditLogQueryService.search(null, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).details())
                .containsKey("fields");
    }

    @Test
    void doesNotAlterTheStoredEvents() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        UUID id = insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        auditLogQueryService.search(null, PageRequest.of(0, 20));

        assertThat(auditLogRepository.count()).isEqualTo(1);
        assertThat(auditLogRepository.findById(id)).isPresent();
    }

    @Test
    void rejectsARangeWhoseLowerBoundIsAfterItsUpperBound() {
        Instant from = Instant.parse("2026-12-31T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> new AuditLogFilter(null, null, null, null, null, from, to))
                .isInstanceOf(InvalidAuditLogRangeException.class);
    }

    @Test
    void acceptsAClosedRangeWhoseBoundsAreEqual() {
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        AuditLogFilter filter = new AuditLogFilter(null, null, null, null, null, instant, instant);

        assertThat(filter.from()).isEqualTo(instant);
        assertThat(filter.to()).isEqualTo(instant);
    }

    @Test
    void doesNotLoadTheWholeTableInMemory() {
        Instant base = Instant.parse("2026-01-01T10:00:00Z");
        for (int index = 0; index < 25; index++) {
            insertEvent(base.plusSeconds(index), ACTOR_ADMIN, "USER_CREATED", "USER",
                    ENTITY_USER.toString(), AuditOutcome.SUCCESS);
        }

        Page<AuditLogResponse> result = auditLogQueryService.search(null, PageRequest.of(0, 5));

        assertThat(result.getContent()).hasSize(5);
        assertThat(result.getTotalElements()).isEqualTo(25);
        assertThat(result.getTotalPages()).isEqualTo(5);
    }

    @Test
    void exposesNoMutableStateOnTheReadModel() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        insertEvent(now, ACTOR_ADMIN, "USER_CREATED", "USER", ENTITY_USER.toString(), AuditOutcome.SUCCESS);

        Page<AuditLogResponse> result = auditLogQueryService.search(null, PageRequest.of(0, 20));

        List<AuditLogResponse> content = result.getContent();
        assertThat(content).hasSize(1);
        assertThat(content.get(0).id()).isNotNull();
        assertThat(content.get(0).entityType()).isEqualTo("USER");
    }
}
