package com.sdigital.platform.dashboard.service;

import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.dashboard.dto.DashboardSummaryResponse;
import com.sdigital.platform.dashboard.dto.EntityStatusStatistics;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DashboardService}. The repositories are mocked: only the
 * aggregation logic is exercised here; the actual count queries are covered against
 * a real PostgreSQL container by {@code DashboardRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AgencyRepository agencyRepository;

    @Mock
    private FunctionRepository functionRepository;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(userRepository, agencyRepository, functionRepository);
    }

    private void stubCounts(long userTotal, long userActive, long userInactive,
                            long agencyTotal, long agencyActive, long agencyInactive,
                            long functionTotal, long functionActive, long functionInactive) {
        when(userRepository.count()).thenReturn(userTotal);
        when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(userActive);
        when(userRepository.countByStatus(UserStatus.INACTIVE)).thenReturn(userInactive);
        when(agencyRepository.count()).thenReturn(agencyTotal);
        when(agencyRepository.countByStatus(AgencyStatus.ACTIVE)).thenReturn(agencyActive);
        when(agencyRepository.countByStatus(AgencyStatus.INACTIVE)).thenReturn(agencyInactive);
        when(functionRepository.count()).thenReturn(functionTotal);
        when(functionRepository.countByStatus(FunctionStatus.ACTIVE)).thenReturn(functionActive);
        when(functionRepository.countByStatus(FunctionStatus.INACTIVE)).thenReturn(functionInactive);
    }

    @Test
    void everyCounterIsZeroWhenThereIsNoData() {
        stubCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.users()).isEqualTo(new EntityStatusStatistics(0, 0, 0));
        assertThat(summary.agencies()).isEqualTo(new EntityStatusStatistics(0, 0, 0));
        assertThat(summary.functions()).isEqualTo(new EntityStatusStatistics(0, 0, 0));
    }

    @Test
    void countsMixedStatusesPerFamily() {
        stubCounts(25, 20, 5, 8, 6, 2, 12, 10, 2);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.users()).isEqualTo(new EntityStatusStatistics(25, 20, 5));
        assertThat(summary.agencies()).isEqualTo(new EntityStatusStatistics(8, 6, 2));
        assertThat(summary.functions()).isEqualTo(new EntityStatusStatistics(12, 10, 2));
    }

    @Test
    void totalEqualsActivePlusInactivePerFamily() {
        stubCounts(20, 12, 8, 7, 3, 4, 9, 9, 0);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertThat(summary.users().total())
                .isEqualTo(summary.users().active() + summary.users().inactive());
        assertThat(summary.agencies().total())
                .isEqualTo(summary.agencies().active() + summary.agencies().inactive());
        assertThat(summary.functions().total())
                .isEqualTo(summary.functions().active() + summary.functions().inactive());
    }

    @Test
    void familiesAreCountedIndependentlyFromTheirOwnRepository() {
        stubCounts(25, 20, 5, 8, 6, 2, 12, 10, 2);

        dashboardService.getSummary();

        verify(userRepository).count();
        verify(userRepository).countByStatus(UserStatus.ACTIVE);
        verify(userRepository).countByStatus(UserStatus.INACTIVE);
        verify(agencyRepository).count();
        verify(agencyRepository).countByStatus(AgencyStatus.ACTIVE);
        verify(agencyRepository).countByStatus(AgencyStatus.INACTIVE);
        verify(functionRepository).count();
        verify(functionRepository).countByStatus(FunctionStatus.ACTIVE);
        verify(functionRepository).countByStatus(FunctionStatus.INACTIVE);
        verifyNoMoreInteractions(userRepository, agencyRepository, functionRepository);
    }

    @Test
    void generatedAtIsSetWithProjectTimeType() {
        stubCounts(1, 1, 0, 1, 1, 0, 1, 1, 0);
        Instant before = Instant.now();

        DashboardSummaryResponse summary = dashboardService.getSummary();

        Instant after = Instant.now();
        assertThat(summary.generatedAt()).isNotNull()
                .isBetween(before, after);
    }
}
