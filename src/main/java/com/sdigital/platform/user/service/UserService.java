package com.sdigital.platform.user.service;

import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserNotFoundException;
import com.sdigital.platform.user.mapper.UserMapper;
import com.sdigital.platform.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Business operations for the user module.
 *
 * <p>Responsibilities: email normalization, uniqueness enforcement and status
 * transitions. Persistence stays in the repository, mapping stays in the mapper.</p>
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        CreateUserRequest normalized = normalize(request);
        ensureEmailAvailable(normalized.email(), null);
        User user = userMapper.toEntity(normalized);
        return userMapper.toResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return userMapper.toResponse(findUserOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> getAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(userMapper::toResponse);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = findUserOrThrow(id);
        UpdateUserRequest normalized = normalize(request);
        ensureEmailAvailable(normalized.email(), id);
        userMapper.applyUpdate(user, normalized);
        return userMapper.toResponse(userRepository.save(user));
    }

    /**
     * Deactivates a user by setting its status to {@link UserStatus#INACTIVE}.
     * The row is never deleted.
     */
    @Transactional
    public UserResponse deactivate(UUID id) {
        User user = findUserOrThrow(id);
        user.setStatus(UserStatus.INACTIVE);
        return userMapper.toResponse(userRepository.save(user));
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
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
        return new CreateUserRequest(normalizeEmail(request.email()), request.firstName(), request.lastName());
    }

    private UpdateUserRequest normalize(UpdateUserRequest request) {
        return new UpdateUserRequest(normalizeEmail(request.email()), request.firstName(), request.lastName());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
