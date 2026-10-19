package com.sdigital.platform.user.service;

import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserIdentityService userIdentityService;

    @Test
    void resolveCurrentLocalUser_returnsLinkedActiveUser_whenSubMatchesAndActive() {
        User user = new User("ada@example.com", "Ada", "Lovelace");
        UUID id = UUID.randomUUID();
        // Reflection not needed; set via setters if we want, but not necessary for fields accessed.
        // Use repository to return this user.
        SecurityContextHolder.getContext().setAuthentication(jwtAuth("sub-123"));

        when(userRepository.findByKeycloakId("sub-123")).thenReturn(Optional.of(user));

        Optional<User> result = userIdentityService.resolveCurrentLocalUser();

        assertThat(result).isPresent().contains(user);
        verify(userRepository).findByKeycloakId("sub-123");
        verifyNoMoreInteractions(userRepository);

        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveCurrentLocalUser_returnsLinkedInactiveUser_whenSubMatchesButInactive() {
        User user = new User("ada@example.com", "Ada", "Lovelace");
        user.setStatus(UserStatus.INACTIVE);
        SecurityContextHolder.getContext().setAuthentication(jwtAuth("sub-456"));

        when(userRepository.findByKeycloakId("sub-456")).thenReturn(Optional.of(user));

        Optional<User> result = userIdentityService.resolveCurrentLocalUser();

        assertThat(result).isPresent().contains(user);
        verify(userRepository).findByKeycloakId("sub-456");

        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveCurrentLocalUser_returnsEmpty_whenNoLocalUserLinkedToSub() {
        SecurityContextHolder.getContext().setAuthentication(jwtAuth("sub-unknown"));

        when(userRepository.findByKeycloakId("sub-unknown")).thenReturn(Optional.empty());

        Optional<User> result = userIdentityService.resolveCurrentLocalUser();

        assertThat(result).isEmpty();
        verify(userRepository).findByKeycloakId("sub-unknown");

        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveCurrentLocalUser_returnsEmpty_whenAuthenticationIsAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        Optional<User> result = userIdentityService.resolveCurrentLocalUser();

        assertThat(result).isEmpty();
        verifyNoInteractions(userRepository);

        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveCurrentLocalUser_returnsEmpty_whenSubIsBlank() {
        SecurityContextHolder.getContext().setAuthentication(jwtAuth("   "));

        Optional<User> result = userIdentityService.resolveCurrentLocalUser();

        assertThat(result).isEmpty();
        verifyNoInteractions(userRepository);

        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveCurrentLocalUser_returnsLinkedUser_whenMultipleUsersExistButOnlyOneLinked() {
        User linked = new User("linked@example.com", "L", "I");
        User other = new User("other@example.com", "O", "T");
        SecurityContextHolder.getContext().setAuthentication(jwtAuth("sub-linked"));

        when(userRepository.findByKeycloakId("sub-linked")).thenReturn(Optional.of(linked));

        Optional<User> result = userIdentityService.resolveCurrentLocalUser();

        assertThat(result).isPresent().contains(linked);
        verify(userRepository).findByKeycloakId("sub-linked");
        verifyNoMoreInteractions(userRepository);

        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveCurrentLocalUser_returnsEmpty_whenTokenHasNoSubClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("scope", "profile email")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        Optional<User> result = userIdentityService.resolveCurrentLocalUser();

        assertThat(result).isEmpty();
        verifyNoInteractions(userRepository);

        SecurityContextHolder.clearContext();
    }

    private static JwtAuthenticationToken jwtAuth(String sub) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", sub)
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
