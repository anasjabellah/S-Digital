# Keycloak Role Management Implementation Plan — Étape 4E.2

Last commit: `b747cab feat(user): add agency and function assignments`  
Branch: `main`  
Status: READ-ONLY planning (no code/config/migration/Keycloak/Postgres changes)

## 1) Verified context (aligned with audit)
- Resource server: OAuth2 JWT validated by `KeycloakJwtDecoderConfig` (JWKS + issuer). Fails closed if `KEYCLOAK_ISSUER_URI` missing.
- Role mapping: `KeycloakRolesConverter` maps `realm_access.roles` → `ROLE_*`, trusted `resource_access.<client>.roles` → `CLIENT_<client>_*` (default trusted `s-digital-api`). Scopes ignored.
- Identity: `KeycloakIdentityResolver` uses only `sub`; `UserIdentityService` links local `User` by `keycloakId == sub` (no auto-provisioning). `LocalUserActiveGuardFilter` enforces linked+ACTIVE.
- RBAC: `SecurityConfig` protects Users/Agencies/Functions (GET ADMIN+MANAGER, writes+activate ADMIN). Default deny for `/api/v1/**`.
- User API: manages profile/status/agency/function only; `keycloakId` not exposed; `UserResponse` has no role fields.
- No Keycloak Admin client/dependency/config exists.

## 2) Technical plan (ordered, testable)

### Phase 0 — Preconditions (non-blocking verification)
1. Confirm Keycloak realm name, issuer URI, and backend client id used by tokens.
2. Identify target users (must have `keycloakId` set) to test against.
3. Ensure env vars strategy documented (no secrets in Git).

### Phase 1 — Keycloak client/configuration (manual/external, preparation only)
- **Create confidential client** (e.g. `s-digital-backend-admin`) for backend.
- **Enable Service Account Roles** (client credentials).
- **Assign minimal permissions** to service account (see §4). Prefer realm roles: `manage-users`, `view-users` (Keycloak Admin REST). Avoid broad `realm-admin` unless strictly justified.
- **Note**: Client credentials flow for server-to-server; tokens used only by backend service.

### Phase 2 — Dependencies/config (proposal, no addition now)
- **Dependency** (if using official client): `org.keycloak:keycloak-admin-client` (align version with Keycloak 26.5.2). Or use `WebClient/RestTemplate` with Admin REST.
- **Properties** (env vars only):
  - `KEYCLOAK_ADMIN_SERVER_URL`
  - `KEYCLOAK_ADMIN_REALM`
  - `KEYCLOAK_ADMIN_CLIENT_ID`
  - `KEYCLOAK_ADMIN_CLIENT_SECRET` (secret manager/env)
  - `SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST` (e.g. `MANAGER,USER`) — allowlist of assignable realm roles
- **Config class**: `KeycloakAdminProperties` (validated) or `@ConfigurationProperties`.

### Phase 3 — Service layer
Create `com.sdigital.platform.user.keycloak` package:
- `KeycloakAdminConfig` — build Admin client/REST client with service account token (handle token refresh). Fail closed on misconfig.
- `KeycloakRoleAdminService` — methods:
  - `getRealmRolesForUser(UUID userId)` → `UserRolesResponse`
  - `assignRealmRoles(UUID userId, List<String> roles)` 
  - `removeRealmRoles(UUID userId, List<String> roles)`
- **Resolution**: map local `User` → `keycloakId` (must exist). If missing/blank → 404/422 per error strategy.
- **Validation**: allowlist check; prevent self-elevation (caller `sub` vs target `keycloakId`); block assigning/removing `ADMIN` unless explicitly allowed by confirmed policy.
- **Mapping**: convert Keycloak role representations to DTOs (name, type REALM).

### Phase 4 — Controller/DTOs (proposed)
**DTOs** (`com.sdigital.platform.user.dto` or `keycloak` subpackage):
- `UserRoleDto(String name, String type)` — `type` in {`REALM`,`CLIENT`} (start with REALM)
- `UserRolesResponse(List<UserRoleDto> realmRoles, List<UserRoleDto> clientRoles)`
- `AssignRolesRequest(List<@NotBlank String> roles)`
- `RemoveRolesRequest(List<@NotBlank String> roles)`

**Controller**: add to `UserController` or new `UserRoleAdminController` under `/api/v1/users/{id}/roles`:
- `GET /api/v1/users/{id}/roles` — list roles (ADMIN only)
- `POST /api/v1/users/{id}/roles` — assign (ADMIN only)
- `DELETE /api/v1/users/{id}/roles` — remove (ADMIN only)

**Security**: enforce `hasRole("ADMIN")` in `SecurityConfig` (add matchers). Keep `LocalUserActiveGuardFilter` in effect.

### Phase 5 — Error handling
Add handlers in `GlobalExceptionHandler` (or service exceptions):
- `KeycloakUserNotFoundException` → 404
- `KeycloakRoleNotAssignableException` → 403 (allowlist/self-elevation)
- `KeycloakAdminClientException` → 502/503 (Keycloak unavailable/auth failure) — **no stack traces/secrets in response**
- `UserNotLinkedToKeycloakException` → 422 (local user has no `keycloakId`)

### Phase 6 — Tests
- **Unit**: `KeycloakRoleAdminServiceTest` (mock Admin client), allowlist, self-protection, missing keycloakId.
- **Controller**: `UserRoleAdminControllerTest` (security disabled or with mock) — ADMIN only, validation.
- **Security**: `UserRoleAdminAuthorizationSecurityTest` — verify MANAGER/USER denied, ADMIN allowed.
- **Integration** (optional): Testcontainers with mock Keycloak or WireMock; prefer mocks to avoid external dependency in CI.

### Phase 7 — Audit & logging
- Log role changes as **audit events**: `actorSub`, `targetUserId`, `targetKeycloakId` (hash or prefix if needed), `rolesAdded`, `rolesRemoved`, `outcome`. **Never log tokens/secrets/client secret**.
- Use structured logging (MDC) where appropriate.

## 3) Files to create/modify (proposed)
**Create**:
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakAdminConfig.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakAdminProperties.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminService.java`
- `src/main/java/com/sdigital/platform/user/dto/UserRoleDto.java`
- `src/main/java/com/sdigital/platform/user/dto/UserRolesResponse.java`
- `src/main/java/com/sdigital/admin/controller/UserRoleAdminController.java` (or in user controller)
- `src/main/java/com/sdigital/platform/user/keycloak/exception/*` (4 exceptions)
- `src/test/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminServiceTest.java`
- `src/test/java/com/stagibr/platform/common/security/UserRoleAdminAuthorizationSecurityTest.java` (typo? `com/sdigital/...`)
- `src/test/java/com/sdigital/platform/user/controller/UserRoleAdminControllerTest.java`

**Modify**:
- `pom.xml` — add Keycloak Admin client (only if approved; **not proposed to add now**)
- `src/main/resources/application.yml` — admin properties (env vars)
- `src/main/java/com/sdigital/platform/common/exception/GlobalExceptionHandler.java` — handlers
- `src/main/java/com/sdigital/platform/common/security/SecurityConfig.java` — role admin matchers (ADMIN only)
- `src/main/java/com/sdigital/platform/user/controller/UserController.java` (optional extension)
- `src/test/resources/application-test.yml` (if needed)

## 4) Environment variables & Keycloak permissions (minimal)
**Env vars (no secrets in Git)**:
- `KEYCLOAK_ADMIN_SERVER_URL` (e.g. `http://localhost:8180`)
- `KEYCLOAK_ADMIN_REALM` (realm name)
- `KEYCLOAK_ADMIN_CLIENT_ID` (confidential client with service account)
- `KEYCLOAK_ADMIN_CLIENT_SECRET`
- `SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST` (comma-separated)

**Minimal Keycloak permissions (service account)**:
- Realm role: `manage-users` — required to assign/remove/ get roles for users
- Realm role: `view-users` — required for user lookup via Admin API
- Optional: `query-users` — if using different API paths
- **Avoid**: `realm-admin`, `manage-realm`, `manage-clients` unless absolutely necessary.

## 5) Decisions split

### Technical decisions (can be prepared)
- Use service account + client credentials for Admin API
- Start with **realm roles only** (align with `realm_access` mapping)
- Use allowlist + self-protection
- Map errors to RFC7807 without sensitive details
- Prefer mocks for tests
- Add audit logging without secrets
- Use UUID for target (map to keycloakId via local User)

### Business decisions requiring validation by El Mehdi
- **Scope**: realm roles only vs realm + client roles?
- **Allowlist**: which roles exactly (MANAGER/USER? can ADMIN be assignable/revable by API?)
- **Self-protection**: forbid self-modification of ADMIN role?
- **Source of truth**: Keycloak roles as source of truth; is there any need to store roles in Postgres?
- **Availability**: fail closed (503) if Keycloak Admin unavailable vs fallback?
- **Audit retention**: any specific requirements?
- **System accounts**: protection rules for specific users?

## 6) Implementation checklist (for future execution)
- [ ] Confirm business decisions with El Mehdi
- [ ] Configure Keycloak client + service account + minimal permissions (externally)
- [ ] Add dependency/config (if approved)
- [ ] Implement service/DTOs/controller/exception handlers
- [ ] Add security matchers
- [ ] Add tests (unit/integration/security)
- [ ] Manual test: GET/POST/DELETE roles (ADMIN)
- [ ] Verify no role exposure in public User API
- [tag] Review audit logs

## Conclusion
This plan is compatible with existing architecture (OAuth2 resource server, role mapping, identity resolution, RBAC). Implementation requires external Keycloak configuration and business validation. **No changes made to code/migrations/config/Keycloak/Postgres.**