package com.sdigital.platform.user.service;

import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditChanges;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserAgencyNotFoundException;
import com.sdigital.platform.user.exception.UserFunctionNotFoundException;
import com.sdigital.platform.user.exception.UserNotFoundException;
import com.sdigital.platform.user.mapper.UserMapper;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.repository.UserSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Business operations for the user module.
 *
 * <p>Responsibilities: email normalization, uniqueness enforcement and status
 * transitions. Persistence stays in the repository, mapping stays in the mapper.</p>
 */
@Service
public class UserService {

    private static final String AUDIT_ENTITY_USER = "USER";
    private static final String AUDIT_ENTITY_USER_ASSIGNMENT = "USER_ASSIGNMENT";

    private static final String ACTION_USER_CREATED = "USER_CREATED";
    private static final String ACTION_USER_UPDATED = "USER_UPDATED";
    private static final String ACTION_USER_DEACTIVATED = "USER_DEACTIVATED";
    private static final String ACTION_USER_ACTIVATED = "USER_ACTIVATED";
    private static final String ACTION_USER_ASSIGNMENT_CHANGED = "USER_ASSIGNMENT_CHANGED";

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final AgencyRepository agencyRepository;
    private final FunctionRepository functionRepository;
    private final UserIdentityService userIdentityService;
    private final AuditLogService auditLogService;
    private final CurrentActorResolver currentActorResolver;

    public UserService(UserRepository userRepository,
                       UserMapper userMapper,
                       AgencyRepository agencyRepository,
                       FunctionRepository functionRepository,
                       UserIdentityService userIdentityService,
                       AuditLogService auditLogService,
                       CurrentActorResolver currentActorResolver) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.agencyRepository = agencyRepository;
        this.functionRepository = functionRepository;
        this.userIdentityService = userIdentityService;
        this.auditLogService = auditLogService;
        this.currentActorResolver = currentActorResolver;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        CreateUserRequest normalized = normalize(request);
        ensureEmailAvailable(normalized.email(), null);
        User user = userMapper.toEntity(normalized);
        user.setAgency(resolveActiveAgency(normalized.agencyId()));
        user.setFunction(resolveActiveFunction(normalized.functionId()));
        User saved = userRepository.save(user);
        auditLogService.record(resolveActorUserId(), ACTION_USER_CREATED, AUDIT_ENTITY_USER,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("status", saved.getStatus().name()));
        return userMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return userMapper.toResponse(findUserOrThrow(id));
    }

    /**
     * Returns the profile of the currently authenticated user.
     *
     * <p>The local user is resolved exclusively from the authenticated JWT {@code sub} through
     * the shared {@link UserIdentityService} identity-resolution flow: no email lookup, no
     * request-supplied identifier, no automatic provisioning and no exposure of
     * {@code keycloakId}.</p>
     *
     * <p>For unlinked or INACTIVE identities this method is never reached in production: the
     * existing {@code LocalUserActiveGuardFilter} already refuses such requests with a 403
     * before the controller runs. If resolution is nonetheless empty (defensive net only), the
     * resolver's own {@link IllegalStateException} contract is reused; no new HTTP status or
     * security policy is introduced here.</p>
     */
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        User user = userIdentityService.resolveCurrentLocalUser()
                .orElseThrow(() -> new IllegalStateException(
                        "Linked ACTIVE local identity required for the current-user profile."));
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> getAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(userMapper::toResponse);
    }

    /**
     * Returns a page of users narrowed by optional filters.
     *
     * <p>{@code search} is a case-insensitive partial match on email, first name and last
     * name; a blank search is treated as absent. {@code status} is an exact match on the
     * lifecycle status. When both filters are absent the historical unfiltered listing is
     * used, so pagination and sorting behave exactly as before.</p>
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> getAll(String search, UserStatus status, Pageable pageable) {
        if (isBlank(search) && status == null) {
            return getAll(pageable);
        }
        return userRepository.findAll(UserSpecifications.withFilters(search, status), pageable)
                .map(userMapper::toResponse);
    }

    /**
     * Updates the mutable fields of a user and, when it changes, the assignment.
     *
     * <p>Two distinct events are produced, and only when something really changed:</p>
     * <ul>
     *     <li>{@code USER_ASSIGNMENT_CHANGED} when the agency or the function changes;</li>
     *     <li>{@code USER_UPDATED} when at least one of the mutable fields changes, with the
     *         list of the fields that actually differ from their previous value.</li>
     * </ul>
     *
     * <p>An update that leaves every field and every assignment untouched changes nothing and
     * therefore produces no event: the audit trail only records effective mutations.</p>
     */
    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = findUserOrThrow(id);
        UpdateUserRequest normalized = normalize(request);

        UUID previousAgencyId = user.getAgency() == null ? null : user.getAgency().getId();
        UUID previousFunctionId = user.getFunction() == null ? null : user.getFunction().getId();

        ensureEmailAvailable(normalized.email(), id);
        AuditChanges changes = new AuditChanges()
                .track("email", user.getEmail(), normalized.email())
                .track("firstName", user.getFirstName(), normalized.firstName())
                .track("lastName", user.getLastName(), normalized.lastName());
        userMapper.applyUpdate(user, normalized);
        Agency agency = resolveActiveAgency(normalized.agencyId());
        Function function = resolveActiveFunction(normalized.functionId());
        user.setAgency(agency);
        user.setFunction(function);
        User saved = userRepository.save(user);

        UUID newAgencyId = agency == null ? null : agency.getId();
        UUID newFunctionId = function == null ? null : function.getId();

        if (assignmentChanged(previousAgencyId, newAgencyId, previousFunctionId, newFunctionId)) {
            auditLogService.record(resolveActorUserId(), ACTION_USER_ASSIGNMENT_CHANGED, AUDIT_ENTITY_USER_ASSIGNMENT,
                    entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                    assignmentDetails(previousAgencyId, newAgencyId, previousFunctionId, newFunctionId));
        }

        if (!changes.isEmpty()) {
            auditLogService.record(resolveActorUserId(), ACTION_USER_UPDATED, AUDIT_ENTITY_USER,
                    entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("fields", changes.fields()));
        }
        return userMapper.toResponse(saved);
    }

    /**
     * Deactivates a user by setting its status to {@link UserStatus#INACTIVE}.
     * The row is never deleted.
     *
     * <p>A user that is already inactive is left untouched and produces no audit event:
     * the audit trail only records effective mutations.</p>
     */
    @Transactional
    public UserResponse deactivate(UUID id) {
        User user = findUserOrThrow(id);
        UserStatus previousStatus = user.getStatus();
        if (previousStatus == UserStatus.INACTIVE) {
            return userMapper.toResponse(user);
        }
        user.setStatus(UserStatus.INACTIVE);
        User saved = userRepository.save(user);
        auditLogService.record(resolveActorUserId(), ACTION_USER_DEACTIVATED, AUDIT_ENTITY_USER,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return userMapper.toResponse(saved);
    }

    /**
     * Reactivates a user by setting its status to {@link UserStatus#ACTIVE}.
     * The row is never created nor deleted, and no other field is touched.
     *
     * <p>A user that is already active is left untouched and produces no audit event:
     * the audit trail only records effective mutations.</p>
     */
    @Transactional
    public UserResponse activate(UUID id) {
        User user = findUserOrThrow(id);
        UserStatus previousStatus = user.getStatus();
        if (previousStatus == UserStatus.ACTIVE) {
            return userMapper.toResponse(user);
        }
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        auditLogService.record(resolveActorUserId(), ACTION_USER_ACTIVATED, AUDIT_ENTITY_USER,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return userMapper.toResponse(saved);
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * Converts a local identifier into its audit string form. The identifier is always
     * present in production (the JPA lifecycle assigns it on save), but a null is tolerated
     * so a missing identifier can never break the audited mutation itself.
     */
    private static String entityIdOf(UUID id) {
        return id == null ? null : id.toString();
    }

    /**
     * Resolves the local id of the acting user from the authenticated Keycloak identity.
     *
     * <p>See {@link CurrentActorResolver}: the association uses the same rule as the rest of
     * the application (JWT {@code sub} equals {@code app_user.keycloak_id}), the email is
     * never used, no user is created implicitly, and {@code null} is returned when no local
     * user is linked.</p>
     */
    private UUID resolveActorUserId() {
        return currentActorResolver.resolveCurrentActorUserId();
    }

    private static Map<String, Object> assignmentDetails(UUID previousAgencyId,
                                                        UUID newAgencyId,
                                                        UUID previousFunctionId,
                                                        UUID newFunctionId) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (agencyChanged(previousAgencyId, newAgencyId)) {
            details.put("previousAgencyId", previousAgencyId == null ? null : previousAgencyId.toString());
            details.put("agencyId", newAgencyId == null ? null : newAgencyId.toString());
        }
        if (functionChanged(previousFunctionId, newFunctionId)) {
            details.put("previousFunctionId", previousFunctionId == null ? null : previousFunctionId.toString());
            details.put("functionId", newFunctionId == null ? null : newFunctionId.toString());
        }
        return details;
    }

    /**
     * Returns {@code true} when the agency or the function assignment really changed.
     */
    private static boolean assignmentChanged(UUID previousAgencyId,
                                              UUID newAgencyId,
                                              UUID previousFunctionId,
                                              UUID newFunctionId) {
        return agencyChanged(previousAgencyId, newAgencyId) || functionChanged(previousFunctionId, newFunctionId);
    }

    private static boolean agencyChanged(UUID previousAgencyId, UUID newAgencyId) {
        return previousAgencyId == null ? newAgencyId != null : !previousAgencyId.equals(newAgencyId);
    }

    private static boolean functionChanged(UUID previousFunctionId, UUID newFunctionId) {
        return previousFunctionId == null ? newFunctionId != null : !previousFunctionId.equals(newFunctionId);
    }

    /**
     * Ensures the email is not used by another user. When {@code excludedId} is provided
     * (update case), the user being modified is ignored so its own email does not
     * generate a false conflict.
     */
    private void ensureEmailAvailable(String email, UUID excludedId) {
        boolean alreadyUsed = excludedId == null
                ? userRepository.existsByEmail(email)
                : userRepository.existsByEmailAndIdNot(email, excludedId);
        if (alreadyUsed) {
            throw new EmailAlreadyUsedException(email);
        }
    }

    private CreateUserRequest normalize(CreateUserRequest request) {
        return new CreateUserRequest(normalizeEmail(request.email()), request.firstName(), request.lastName(), request.agencyId(), request.functionId());
    }

    private UpdateUserRequest normalize(UpdateUserRequest request) {
        return new UpdateUserRequest(normalizeEmail(request.email()), request.firstName(), request.lastName(), request.agencyId(), request.functionId());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private Agency resolveActiveAgency(UUID agencyId) {
        if (agencyId == null) {
            return null;
        }
        Agency agency = agencyRepository.findById(agencyId)
                .orElseThrow(() -> new UserAgencyNotFoundException(agencyId));
        if (agency.getStatus() != AgencyStatus.ACTIVE) {
            throw new UserAgencyNotFoundException(agencyId);
        }
        return agency;
    }

    private Function resolveActiveFunction(UUID functionId) {
        if (functionId == null) {
            return null;
        }
        Function function = functionRepository.findById(functionId)
                .orElseThrow(() -> new UserFunctionNotFoundException(functionId));
        if (function.getStatus() != FunctionStatus.ACTIVE) {
            throw new UserFunctionNotFoundException(functionId);
        }
        return function;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
