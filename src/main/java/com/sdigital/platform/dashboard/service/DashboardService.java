package com.sdigital.platform.dashboard.service;

import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.dashboard.dto.DashboardSummaryResponse;
import com.sdigital.platform.dashboard.dto.EntityStatusStatistics;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Read-only aggregation of the global platform statistics.
 *
 * <p>Every counter is computed by the database through derived count queries
 * ({@code count()} and {@code countByStatus(...)}), so no entity is ever loaded into
 * memory and the result stays correct regardless of the number of rows. The three
 * families are counted independently from their own table, which rules out any
 * double-counting caused by the user/agency/function relationships.</p>
 *
 * <p>The whole aggregation runs in a single read-only transaction so the statements
 * share one connection and no status is ever mutated. Because PostgreSQL runs at
 * READ COMMITTED by default, each count statement sees a data snapshot taken at the
 * start of that statement: a concurrent commit landing between two counts can be
 * reflected in one counter and not the other, which is acceptable for these
 * "at a glance" statistics.</p>
 */
@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final AgencyRepository agencyRepository;
    private final FunctionRepository functionRepository;

    public DashboardService(UserRepository userRepository,
                            AgencyRepository agencyRepository,
                            FunctionRepository functionRepository) {
        this.userRepository = userRepository;
        this.agencyRepository = agencyRepository;
        this.functionRepository = functionRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary() {
        EntityStatusStatistics users = new EntityStatusStatistics(
                userRepository.count(),
                userRepository.countByStatus(UserStatus.ACTIVE),
                userRepository.countByStatus(UserStatus.INACTIVE));

        EntityStatusStatistics agencies = new EntityStatusStatistics(
                agencyRepository.count(),
                agencyRepository.countByStatus(AgencyStatus.ACTIVE),
                agencyRepository.countByStatus(AgencyStatus.INACTIVE));

        EntityStatusStatistics functions = new EntityStatusStatistics(
                functionRepository.count(),
                functionRepository.countByStatus(FunctionStatus.ACTIVE),
                functionRepository.countByStatus(FunctionStatus.INACTIVE));

        return new DashboardSummaryResponse(users, agencies, functions, Instant.now());
    }
}
