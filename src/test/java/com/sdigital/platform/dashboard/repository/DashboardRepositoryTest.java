package com.sdigital.platform.dashboard.repository;

import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the count queries backing the dashboard, running against a real
 * PostgreSQL 16 container. The derived {@code count()} / {@code countByStatus(...)} methods
 * are validated against actual SQL: empty tables must return zero (not {@code null}), mixed
 * statuses must be counted exactly, and the user/agency/function relationships must never
 * cause double counting.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DashboardRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AgencyRepository agencyRepository;

    @Autowired
    private FunctionRepository functionRepository;

    @Test
    void emptyTablesReturnZero() {
        assertThat(userRepository.count()).isZero();
        assertThat(userRepository.countByStatus(UserStatus.ACTIVE)).isZero();
        assertThat(userRepository.countByStatus(UserStatus.INACTIVE)).isZero();
        assertThat(agencyRepository.count()).isZero();
        assertThat(agencyRepository.countByStatus(AgencyStatus.ACTIVE)).isZero();
        assertThat(agencyRepository.countByStatus(AgencyStatus.INACTIVE)).isZero();
        assertThat(functionRepository.count()).isZero();
        assertThat(functionRepository.countByStatus(FunctionStatus.ACTIVE)).isZero();
        assertThat(functionRepository.countByStatus(FunctionStatus.INACTIVE)).isZero();
    }

    @Test
    void activeAndInactiveAreCountedExactly() {
        saveAgency("AG-ACTIVE-01", AgencyStatus.ACTIVE);
        saveAgency("AG-ACTIVE-02", AgencyStatus.ACTIVE);
        saveAgency("AG-INACTIVE-01", AgencyStatus.INACTIVE);

        saveFunction("F-ACTIVE-01", FunctionStatus.ACTIVE);
        saveFunction("F-INACTIVE-01", FunctionStatus.INACTIVE);
        saveFunction("F-INACTIVE-02", FunctionStatus.INACTIVE);

        saveUser("a@example.com", UserStatus.ACTIVE);
        saveUser("b@example.com", UserStatus.INACTIVE);

        assertThat(agencyRepository.count()).isEqualTo(3);
        assertThat(agencyRepository.countByStatus(AgencyStatus.ACTIVE)).isEqualTo(2);
        assertThat(agencyRepository.countByStatus(AgencyStatus.INACTIVE)).isEqualTo(1);

        assertThat(functionRepository.count()).isEqualTo(3);
        assertThat(functionRepository.countByStatus(FunctionStatus.ACTIVE)).isEqualTo(1);
        assertThat(functionRepository.countByStatus(FunctionStatus.INACTIVE)).isEqualTo(2);

        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(userRepository.countByStatus(UserStatus.ACTIVE)).isEqualTo(1);
        assertThat(userRepository.countByStatus(UserStatus.INACTIVE)).isEqualTo(1);
    }

    @Test
    void relationshipsDoNotCauseDoubleCounting() {
        Agency activeAgency = saveAgency("AG-01", AgencyStatus.ACTIVE);
        Agency inactiveAgency = saveAgency("AG-02", AgencyStatus.INACTIVE);
        Function activeFunction = saveFunction("F-01", FunctionStatus.ACTIVE);
        Function inactiveFunction = saveFunction("F-02", FunctionStatus.INACTIVE);

        User first = new User("linked-1@example.com", "Linked", "One");
        first.setAgency(activeAgency);
        first.setFunction(activeFunction);
        userRepository.saveAndFlush(first);

        User second = new User("linked-2@example.com", "Linked", "Two");
        second.setAgency(inactiveAgency);
        second.setFunction(inactiveFunction);
        second.setStatus(UserStatus.INACTIVE);
        userRepository.saveAndFlush(second);

        User unlinked = new User("unlinked@example.com", "Unlinked", "Three");
        userRepository.saveAndFlush(unlinked);

        assertThat(userRepository.count()).isEqualTo(3);
        assertThat(userRepository.countByStatus(UserStatus.ACTIVE)).isEqualTo(2);
        assertThat(userRepository.countByStatus(UserStatus.INACTIVE)).isEqualTo(1);

        assertThat(agencyRepository.count()).isEqualTo(2);
        assertThat(agencyRepository.countByStatus(AgencyStatus.ACTIVE)).isEqualTo(1);
        assertThat(agencyRepository.countByStatus(AgencyStatus.INACTIVE)).isEqualTo(1);

        assertThat(functionRepository.count()).isEqualTo(2);
        assertThat(functionRepository.countByStatus(FunctionStatus.ACTIVE)).isEqualTo(1);
        assertThat(functionRepository.countByStatus(FunctionStatus.INACTIVE)).isEqualTo(1);
    }

    @Test
    void countsRemainCorrectWithManyRecords() {
        for (int i = 0; i < 150; i++) {
            saveAgency("AG-" + i, i % 3 == 0 ? AgencyStatus.INACTIVE : AgencyStatus.ACTIVE);
        }

        assertThat(agencyRepository.count()).isEqualTo(150);
        assertThat(agencyRepository.countByStatus(AgencyStatus.INACTIVE)).isEqualTo(50);
        assertThat(agencyRepository.countByStatus(AgencyStatus.ACTIVE)).isEqualTo(100);
    }

    private Agency saveAgency(String code, AgencyStatus status) {
        Agency agency = new Agency(code, "Agency " + code);
        agency.setStatus(status);
        return agencyRepository.saveAndFlush(agency);
    }

    private Function saveFunction(String code, FunctionStatus status) {
        Function function = new Function(code, "Function " + code);
        function.setStatus(status);
        return functionRepository.saveAndFlush(function);
    }

    private void saveUser(String email, UserStatus status) {
        User user = new User(email, "First", "Last");
        user.setStatus(status);
        userRepository.saveAndFlush(user);
    }
}
