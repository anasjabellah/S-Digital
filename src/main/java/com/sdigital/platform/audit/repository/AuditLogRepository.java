package com.sdigital.platform.audit.repository;

import com.sdigital.platform.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link AuditLog}.
 *
 * <p>Audit entries are append-only: the application only creates them through
 * {@code AuditLogService}. The read side added for step 4H.5 uses the
 * {@link JpaSpecificationExecutor} query methods ({@code findAll} / {@code count}) to
 * apply the consultation filters in SQL. The mutating methods inherited from the
 * specification executor ({@code delete(Specification)}, {@code update(Specification, ...)})
 * are never called by the application: no code path removes or alters an existing audit
 * entry, and the API exposes no route to do so.</p>
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>,
        JpaSpecificationExecutor<AuditLog> {
}
