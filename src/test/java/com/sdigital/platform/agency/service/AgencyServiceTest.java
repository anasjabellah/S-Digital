package com.sdigital.platform.agency.service;

import com.sdigital.platform.agency.AgencyTestData;
import com.sdigital.platform.agency.dto.AgencyResponse;
import com.sdigital.platform.agency.dto.CreateAgencyRequest;
import com.sdigital.platform.agency.dto.UpdateAgencyRequest;
import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.exception.AgencyCodeAlreadyUsedException;
import com.sdigital.platform.agency.exception.AgencyNotFoundException;
import com.sdigital.platform.agency.mapper.AgencyMapper;
import com.sdigital.platform.agency.repository.AgencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgencyService}. The repository is mocked; the mapper is the real
 * implementation so mapping is exercised together with the business rules.
 */
@ExtendWith(MockitoExtension.class)
class AgencyServiceTest {

    @Mock
    private AgencyRepository agencyRepository;

    private AgencyService agencyService;

    @BeforeEach
    void setUp() {
        agencyService = new AgencyService(agencyRepository, new AgencyMapper());
    }

    @Test
    void createNormalizesCodeAndNameAndPersists() {
        CreateAgencyRequest request = new CreateAgencyRequest("  ag-paris-01 ", "  Agency Paris 01 ");
        when(agencyRepository.existsByCode("AG-PARIS-01")).thenReturn(false);
        when(agencyRepository.save(any(Agency.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgencyResponse response = agencyService.create(request);

        assertThat(response.code()).isEqualTo("AG-PARIS-01");
        assertThat(response.name()).isEqualTo("Agency Paris 01");
        assertThat(response.status()).isEqualTo(AgencyStatus.ACTIVE);
        verify(agencyRepository).existsByCode("AG-PARIS-01");
        verify(agencyRepository).save(any(Agency.class));
    }

    @Test
    void createRejectsAlreadyUsedCode() {
        CreateAgencyRequest request = new CreateAgencyRequest("AG-PARIS-01", "Agency Paris 01");
        when(agencyRepository.existsByCode("AG-PARIS-01")).thenReturn(true);

        assertThatThrownBy(() -> agencyService.create(request))
                .isInstanceOf(AgencyCodeAlreadyUsedException.class);

        verify(agencyRepository, never()).save(any(Agency.class));
    }

    @Test
    void getByIdThrowsWhenAgencyDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(agencyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agencyService.getById(id))
                .isInstanceOf(AgencyNotFoundException.class);
    }

    @Test
    void getByIdReturnsMappedAgency() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        Agency agency = AgencyTestData.agency(id, "AG-PARIS-01", "Agency Paris 01", createdAt, updatedAt);
        when(agencyRepository.findById(id)).thenReturn(Optional.of(agency));

        AgencyResponse response = agencyService.getById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.code()).isEqualTo("AG-PARIS-01");
        assertThat(response.status()).isEqualTo(AgencyStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void updateAllowsUnchangedCodeOfSameAgency() {
        UUID id = UUID.randomUUID();
        Agency agency = AgencyTestData.agency(id, "AG-PARIS-01", "Agency Paris 01", Instant.now(), Instant.now());
        UpdateAgencyRequest request = new UpdateAgencyRequest("  ag-paris-01 ", "  Agency Paris Centre ");
        when(agencyRepository.findById(id)).thenReturn(Optional.of(agency));
        when(agencyRepository.existsByCodeAndIdNot("AG-PARIS-01", id)).thenReturn(false);
        when(agencyRepository.save(any(Agency.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgencyResponse response = agencyService.update(id, request);

        assertThat(response.code()).isEqualTo("AG-PARIS-01");
        assertThat(response.name()).isEqualTo("Agency Paris Centre");
        verify(agencyRepository).existsByCodeAndIdNot("AG-PARIS-01", id);
        verify(agencyRepository).save(agency);
    }

    @Test
    void updateRejectsCodeUsedByAnotherAgency() {
        UUID id = UUID.randomUUID();
        Agency agency = AgencyTestData.agency(id, "AG-PARIS-01", "Agency Paris 01", Instant.now(), Instant.now());
        UpdateAgencyRequest request = new UpdateAgencyRequest("AG-LYON-01", "Agency Paris 01");
        when(agencyRepository.findById(id)).thenReturn(Optional.of(agency));
        when(agencyRepository.existsByCodeAndIdNot("AG-LYON-01", id)).thenReturn(true);

        assertThatThrownBy(() -> agencyService.update(id, request))
                .isInstanceOf(AgencyCodeAlreadyUsedException.class);

        verify(agencyRepository, never()).save(any(Agency.class));
    }

    @Test
    void updateDoesNotModifyProtectedFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        Agency agency = AgencyTestData.agency(id, "AG-PARIS-01", "Agency Paris 01", createdAt, updatedAt);
        UpdateAgencyRequest request = new UpdateAgencyRequest("AG-PARIS-01", "Agency Paris Centre");
        when(agencyRepository.findById(id)).thenReturn(Optional.of(agency));
        when(agencyRepository.existsByCodeAndIdNot("AG-PARIS-01", id)).thenReturn(false);
        when(agencyRepository.save(any(Agency.class))).thenAnswer(invocation -> invocation.getArgument(0));

        agencyService.update(id, request);

        assertThat(agency.getId()).isEqualTo(id);
        assertThat(agency.getCreatedAt()).isEqualTo(createdAt);
        assertThat(agency.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(agency.getStatus()).isEqualTo(AgencyStatus.ACTIVE);
    }

    @Test
    void deactivateSetsStatusInactiveWithoutDeleting() {
        UUID id = UUID.randomUUID();
        Agency agency = AgencyTestData.agency(id, "AG-PARIS-01", "Agency Paris 01", Instant.now(), Instant.now());
        when(agencyRepository.findById(id)).thenReturn(Optional.of(agency));
        when(agencyRepository.save(any(Agency.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgencyResponse response = agencyService.deactivate(id);

        assertThat(response.status()).isEqualTo(AgencyStatus.INACTIVE);
        verify(agencyRepository).save(agency);
        verify(agencyRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void activateRestoresStatusActiveAndPreservesOtherFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        Agency agency = AgencyTestData.agency(id, "AG-PARIS-01", "Agency Paris 01", createdAt, updatedAt);
        agency.setStatus(AgencyStatus.INACTIVE);
        when(agencyRepository.findById(id)).thenReturn(Optional.of(agency));
        when(agencyRepository.save(any(Agency.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgencyResponse response = agencyService.activate(id);

        assertThat(response.status()).isEqualTo(AgencyStatus.ACTIVE);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.code()).isEqualTo("AG-PARIS-01");
        assertThat(response.name()).isEqualTo("Agency Paris 01");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        verify(agencyRepository).save(agency);
        verify(agencyRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void activateThrowsWhenAgencyDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(agencyRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agencyService.activate(id))
                .isInstanceOf(AgencyNotFoundException.class);

        verify(agencyRepository, never()).save(any(Agency.class));
    }

    @Test
    void getAllReturnsMappedPage() {
        Agency agency = AgencyTestData.agency(UUID.randomUUID(), "AG-PARIS-01", "Agency Paris 01",
                Instant.now(), Instant.now());
        Pageable pageable = PageRequest.of(0, 10);
        when(agencyRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(agency), pageable, 1));

        Page<AgencyResponse> page = agencyService.getAll(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).code()).isEqualTo("AG-PARIS-01");
    }

    @Test
    void getAllWithoutFiltersKeepsTheUnfilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(agencyRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        agencyService.getAll(null, null, pageable);

        verify(agencyRepository).findAll(pageable);
        verify(agencyRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllTreatsBlankSearchAsAbsent() {
        Pageable pageable = PageRequest.of(0, 10);
        when(agencyRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        agencyService.getAll("   ", null, pageable);

        verify(agencyRepository).findAll(pageable);
        verify(agencyRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllWithSearchUsesTheFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        Agency agency = AgencyTestData.agency(UUID.randomUUID(), "AG-PARIS-01", "Agency Paris 01",
                Instant.now(), Instant.now());
        when(agencyRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(agency), pageable, 1));

        Page<AgencyResponse> page = agencyService.getAll("paris", null, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(agencyRepository, never()).findAll(pageable);
    }

    @Test
    void getAllWithStatusOnlyUsesTheFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(agencyRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        agencyService.getAll(null, AgencyStatus.INACTIVE, pageable);

        verify(agencyRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void getAllCombinesSearchAndStatus() {
        Pageable pageable = PageRequest.of(0, 10);
        when(agencyRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        agencyService.getAll("  paris ", AgencyStatus.ACTIVE, pageable);

        verify(agencyRepository).findAll(any(Specification.class), eq(pageable));
        verify(agencyRepository, never()).findAll(pageable);
    }
}
