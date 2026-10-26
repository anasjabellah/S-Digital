package com.sdigital.platform.common.exception;

import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.email.entity.EmailTemplate;
import com.sdigital.platform.email.repository.EmailTemplateRepository;
import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for the check-then-act uniqueness races, against a real PostgreSQL 16
 * container running the Flyway schema.
 *
 * <p>Each test releases several writers at the same instant on the same unique value. The
 * database constraint must keep exactly one row, every losing writer must fail with a
 * {@link DataIntegrityViolationException}, and the global handler must translate that
 * failure to HTTP 409 — never to a 500.</p>
 *
 * <p>The tests do not use the live database: they run in a disposable Testcontainer and
 * delete the rows they create.</p>
 */
@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UniqueConstraintRaceIntegrationTest {

    private static final int WRITERS = 6;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AgencyRepository agencyRepository;

    @Autowired
    private FunctionRepository functionRepository;

    @Autowired
    private EmailTemplateRepository emailTemplateRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void cleanUp() {
        emailTemplateRepository.deleteAll();
        userRepository.deleteAll();
        functionRepository.deleteAll();
        agencyRepository.deleteAll();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("concurrent duplicate emails: one row, every loser is a 409")
    void concurrentDuplicateEmailNeverProducesServerError() {
        String email = "race-" + UUID.randomUUID() + "@example.com";

        List<Attempt> attempts = runConcurrently(() ->
                saveInTransaction(() -> userRepository.save(new User(email, "Race", "User"))));

        assertOneWinnerAndConflicts(attempts, "uk_app_user_email");
        assertThat(userRepository.findAll())
                .filteredOn(user -> email.equals(user.getEmail()))
                .hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("concurrent duplicate keycloak_id links: one row, every loser is a 409")
    void concurrentDuplicateKeycloakIdNeverProducesServerError() {
        String keycloakId = "kc-race-" + UUID.randomUUID();

        List<Attempt> attempts = runConcurrently(() -> saveInTransaction(() -> {
            User user = new User("kc-race-" + UUID.randomUUID() + "@example.com", "Race", "User");
            user.setKeycloakId(keycloakId);
            userRepository.save(user);
        }));

        assertOneWinnerAndConflicts(attempts, "uk_app_user_keycloak_id");
        assertThat(userRepository.findAll())
                .filteredOn(user -> keycloakId.equals(user.getKeycloakId()))
                .hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("concurrent duplicate agency codes: one row, every loser is a 409")
    void concurrentDuplicateAgencyCodeNeverProducesServerError() {
        String code = "AG-RACE-" + UUID.randomUUID();

        List<Attempt> attempts = runConcurrently(() ->
                saveInTransaction(() -> agencyRepository.save(new Agency(code, "Race Agency"))));

        assertOneWinnerAndConflicts(attempts, "uk_agency_code");
        assertThat(agencyRepository.findAll())
                .filteredOn(agency -> code.equals(agency.getCode()))
                .hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("concurrent duplicate function codes: one row, every loser is a 409")
    void concurrentDuplicateFunctionCodeNeverProducesServerError() {
        String code = "FN-RACE-" + UUID.randomUUID();

        List<Attempt> attempts = runConcurrently(() ->
                saveInTransaction(() -> functionRepository.save(new Function(code, "Race Function"))));

        assertOneWinnerAndConflicts(attempts, "uk_function_code");
        assertThat(functionRepository.findAll())
                .filteredOn(function -> code.equals(function.getCode()))
                .hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("concurrent duplicate email-template codes: one row, every loser is a 409")
    void concurrentDuplicateEmailTemplateCodeNeverProducesServerError() {
        String code = "TPL-RACE-" + UUID.randomUUID();

        List<Attempt> attempts = runConcurrently(() ->
                saveInTransaction(() -> emailTemplateRepository.save(
                        new EmailTemplate(code, "Race Template", "Subject", "Body", "fr", List.of()))));

        assertOneWinnerAndConflicts(attempts, "uk_email_template_code");
        assertThat(emailTemplateRepository.findAll())
                .filteredOn(template -> code.equals(template.getCode()))
                .hasSize(1);
    }

    private void assertOneWinnerAndConflicts(List<Attempt> attempts, String expectedConstraint) {
        assertThat(attempts).hasSize(WRITERS);
        assertThat(attempts).filteredOn(Attempt::succeeded).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.succeeded())
                .isNotEmpty()
                .allSatisfy(attempt -> {
                    assertThat(attempt.error()).isInstanceOf(DataIntegrityViolationException.class);
                    assertThat(attempt.httpStatus()).isEqualTo(409);
                    // The real driver exception must expose the constraint, otherwise the
                    // handler could only answer the generic conflict detail.
                    assertThat(DatabaseConstraints.uniqueConstraintName(attempt.error()))
                            .contains(expectedConstraint);
                });
    }

    /**
     * Runs the write through an explicit transaction, so the constraint is checked at
     * commit — exactly as when a {@code @Transactional} service returns. The commit-time
     * failure is what the production handler must translate to 409.
     */
    private void saveInTransaction(Runnable write) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> write.run());
    }

    private List<Attempt> runConcurrently(Runnable write) {
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch ready = new CountDownLatch(WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < WRITERS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        write.run();
                        return new Attempt(null, 201);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return new Attempt(new IllegalStateException("Concurrent write interrupted", ex), 500);
                    } catch (RuntimeException ex) {
                        return new Attempt(ex, httpStatusOf(ex));
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(30, TimeUnit.SECONDS));
            }
            return attempts;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent write interrupted", ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Concurrent write failed to complete", ex);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Reproduces the status the HTTP layer would answer for this failure: the handler is
     * the production one, so a 500 here would mean the race is still surfacing as a server
     * error.
     */
    private int httpStatusOf(RuntimeException ex) {
        if (ex instanceof DataIntegrityViolationException integrityViolation) {
            return handler.handleDataIntegrityViolation(integrityViolation).getStatus();
        }
        return 500;
    }

    private record Attempt(RuntimeException error, int httpStatus) {

        boolean succeeded() {
            return error == null;
        }
    }
}
