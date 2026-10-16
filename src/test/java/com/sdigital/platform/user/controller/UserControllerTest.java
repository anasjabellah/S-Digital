package com.sdigital.platform.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserNotFoundException;
import com.sdigital.platform.user.service.UserService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP tests for {@link UserController} and {@link GlobalExceptionHandler}.
 * {@link UserService} is mocked: only the HTTP layer is exercised here.
 *
 * <p>Security filters are disabled ({@code addFilters = false}) because this slice focuses
 * on the controller/handler contract; authentication and authorization are covered by
 * {@code SecurityConfigTest} and {@code PublicEndpointsSecurityTest}.</p>
 */
@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    private UserResponse sample(UUID id) {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        return new UserResponse(id, "bob@example.com", "Bob", "Marley", UserStatus.ACTIVE, created, created);
    }

    @Test
    void createReturns201WithBodyAndLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.create(any(CreateUserRequest.class))).thenReturn(sample(id));
        String body = objectMapper.writeValueAsString(new CreateUserRequest("bob@example.com", "Bob", "Marley"));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/users/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void createReturns400WhenBodyInvalid() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateUserRequest("not-an-email", "", ""));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItems("email", "firstName", "lastName")));
    }

    @Test
    void createReturns400WhenBodyMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createReturns409WhenEmailAlreadyUsed() throws Exception {
        when(userService.create(any(CreateUserRequest.class)))
                .thenThrow(new EmailAlreadyUsedException("bob@example.com"));
        String body = objectMapper.writeValueAsString(new CreateUserRequest("bob@example.com", "Bob", "Marley"));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Email already in use"));
    }

    @Test
    void getByIdReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.getById(id)).thenReturn(sample(id));

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void getByIdReturns404WhenUnknown() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.getById(id)).thenThrow(new UserNotFoundException(id));

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("User not found"));
    }

    @Test
    void getByIdReturns400WhenUuidMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getAllReturnsPagedContentAndCapsPageSize() throws Exception {
        when(userService.getAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample(UUID.randomUUID())), PageRequest.of(0, 100), 1));

        mockMvc.perform(get("/api/v1/users")
                        .param("page", "0")
                        .param("size", "1000")
                        .param("sort", "email,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("bob@example.com"))
                .andExpect(jsonPath("$.totalElements").value(1));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(userService).getAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "email"));
    }

    @Test
    void updateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.update(any(UUID.class), any(UpdateUserRequest.class))).thenReturn(sample(id));
        String body = objectMapper.writeValueAsString(new UpdateUserRequest("bob@example.com", "Bobby", "Marley"));

        mockMvc.perform(put("/api/v1/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/users/{id}", id))
                .andExpect(status().isNoContent());

        verify(userService).deactivate(id);
    }

    @Test
    void unexpectedExceptionReturns500WithGenericMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.getById(id)).thenThrow(new RuntimeException("sensitive internal detail"));

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("sensitive"))));
    }
}
