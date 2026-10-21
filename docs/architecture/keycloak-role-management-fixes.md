# Keycloak Role Management — Targeted Fixes (H1, M2, M3) — Étape 4E.6

Reference review: `docs/architecture/keycloak-role-management-qa-review.md` (conclusion: NO-GO).
Last confirmed commit: `b747cab feat(user): add agency and function assignments`.
Branch: `main`.

This step fixes only the three confirmed anomalies and adds the tests needed to verify
them. No commit, no push, no change to shared Keycloak or PostgreSQL.

## 1) H1 — Disabled Keycloak account is now refused

### Root cause
`KeycloakRoleAdminService.resolveTarget(targetUserId, requireEnabled)` was called with
`requireEnabled=false` in every path, so the `isUserEnabled` check and the
"account disabled" `RoleChangeDeniedException` were unreachable (dead code).

### Fix
- `prepareMutation(...)` now calls `resolveTarget(targetUserId, true)` so the enabled
  check runs **before** any mutation and before the self-modification check.
- `resolveTarget` still runs `findUserId` first (unlinked / not-found semantics unchanged),
  then, when required, `isUserEnabled`; a disabled account throws
  `RoleChangeDeniedException` (mapped to HTTP 403) and the remote mutation is never called.
- Self-modification rule and the order of security controls (config guards → allowlist →
  reserved role → target resolution/enabled → actor → self-modification) are preserved.
- No reactivation, no state change on the Keycloak account.

### Tests
- `KeycloakRoleAdminServiceTest`:
  - `assignRoleRejectsDisabledKeycloakAccountWithoutMutating` — asserts
    `RoleChangeDeniedException` and `verify(never()).addRealmRole(...)`.
  - `removeRoleRejectsDisabledKeycloakAccountWithoutMutating` — asserts
    `RoleChangeDeniedException` and `verify(never()).removeRealmRole(...)`.
  - `assignRoleProceedsWhenKeycloakAccountEnabled` — positive path.
- `UserRoleControllerTest`:
  - `assignRoleReturns403WhenKeycloakAccountDisabled` — HTTP 403, generic detail
    (no "disabled" leak).
  - `removeRoleReturns403WhenKeycloakAccountDisabled` — HTTP 403, generic detail.
- Existing success/self-modification tests updated to stub `isUserEnabled=true` so they
  exercise the new order.

## 2) M2 — Configured timeouts are now applied to the RestClient

### Root cause
`KeycloakAdminProperties.connectTimeout/readTimeout` existed but were never applied;
`KeycloakAdminConfig` passed the shared `RestClient.Builder` unchanged.

### Fix
- `KeycloakAdminConfig` now builds a request factory from the configured timeouts via
  Spring Boot's `ClientHttpRequestFactoryBuilder.detect()` +
  `ClientHttpRequestFactorySettings.withConnectTimeout/withReadTimeout`, and passes
  `restClientBuilder.requestFactory(...)` to the client.
- Uses only existing dependencies (Spring Boot 3.4.1 / spring-web 6.2.1); no version bumps.
  With no Apache HttpClient5 on the classpath, `detect()` selects the JDK-based factory,
  which honours both connect and read timeouts.

### Test
- `KeycloakAdminConfigTest.readTimeoutIsAppliedToTheRestClient` — starts a local
  `HttpServer` that delays every response by 3s; with `readTimeout=300ms` the call aborts
  well before 2.5s and the server never completes the response. This demonstrates the
  timeout is transmitted to the HTTP layer (a default client would wait the full 3s).

## 3) M3 — Mutation errors are controlled, and authz problems are not disguised as outages

### Root cause
`assignRole`/`removeRole` called the client directly; network/timeout/5xx surfaced as a raw
`RuntimeException` (→ generic 500) instead of the intended controlled response, and no
Keycloak status was classified.

### Fix
- `KeycloakAdminClient` now attaches an `onStatus` handler to every Admin call (user
  lookup, enabled lookup, role listing, role resolution, add, remove, and the token
  endpoint) that classifies the upstream status into a new
  `KeycloakUpstreamException`:
  - 401 → `UNAUTHENTICATED`, 403 → `FORBIDDEN`, 429 → `RATE_LIMITED`, other non-2xx → `SERVER_ERROR`.
  - The Keycloak response body is never read or forwarded; only the status code is kept,
    server-side.
- `KeycloakRoleAdminService.guardedKeycloakCall` now wraps the mutations too, and
  `classifyUpstreamFailure` separates:
  - 401/403 (our service account rejected / lacks permission) → `KeycloakIntegrationException`
    (HTTP 500), so a backend authorization/configuration defect is **not** masked as a
    transient outage;
  - 429 / 5xx → `KeycloakUnavailableException` (HTTP 503);
  - any other runtime failure (timeouts, connection refused) → `KeycloakUnavailableException` (503),
    with the cause logged but never serialized.
- New `KeycloakIntegrationException` handled in `GlobalExceptionHandler` → HTTP 500 with a
  generic detail.
- No automatic retry of mutations.

### Tests
- `KeycloakAdminClientTest`: `upstreamUnauthorizedIsClassified`,
  `upstreamForbiddenIsClassified`, `upstreamTooManyRequestsIsClassified`,
  `upstreamServerErrorIsClassified`, `mutationUpstreamFailureIsClassifiedAndNoBodyLeaks`.
- `KeycloakRoleAdminServiceTest`: `assignRoleMutationServerErrorBecomesUnavailable` (503),
  `removeRoleMutationNetworkFailureBecomesUnavailable` (503),
  `mutationForbiddenUpstreamBecomesIntegrationFailureNotUnavailable` (500, not 503).
- `UserRoleControllerTest`: `assignRoleReturns503WhenKeycloakUnavailableOnMutation`,
  `removeRoleReturns503WhenKeycloakUnavailableOnMutation` (generic detail, no leak).

## 4) Tests executed and results

### Targeted (Java 21)
`KeycloakAdminClientTest, KeycloakRoleAdminServiceTest, UserRoleControllerTest,
KeycloakAdminConfigTest, UserRoleAdminAuthorizationSecurityTest`
→ **50 tests, 0 failures, 0 errors, BUILD SUCCESS.**

### Full suite — first run (Java 21)
`mvn.cmd -B test` → **BUILD FAILURE**, 4 errors, all environmental:
`Could not find a valid Docker environment` (Testcontainers `postgres:16`) in
`AgencyRepositoryTest`, `FunctionRepositoryTest`, `UserRepositoryTest`,
`PublicEndpointsSecurityTest`. Docker was not reachable at that moment; unrelated to 4E.6.

### Full suite — rerun after confirming Docker
Docker verified (`docker info` → Server Version 28.5.1), then rerun →
**304 tests, 0 failures, 0 errors, BUILD SUCCESS** (was 288 before 4E.6; +16 tests).

## 5) Files changed in this step

Production:
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminService.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakAdminClient.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakAdminConfig.java`
- `src/main/java/com/sdigital/platform/user/keycloak/exception/KeycloakUpstreamException.java` (new)
- `src/main/java/com/sdigital/platform/user/keycloak/exception/KeycloakIntegrationException.java` (new)
- `src/main/java/com/sdigital/platform/common/exception/GlobalExceptionHandler.java`

Tests:
- `src/test/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminServiceTest.java`
- `src/test/java/com/sdigital/platform/user/keycloak/KeycloakAdminClientTest.java`
- `src/test/java/com/sdigital/platform/user/keycloak/KeycloakAdminConfigTest.java` (new)
- `src/test/java/com/sdigital/platform/user/controller/UserRoleControllerTest.java`

## 6) Residual / open risks (not blocking)

- **L5** GET `/roles` still filters by allowlist and cannot distinguish "no roles" from
  "roles present but not manageable" — documented behaviour, unchanged.
- **L6/L7** Audit still logs only the self-modification denial reason; constants
  `REASON_RESERVED_ROLE` / `REASON_NOT_ALLOWLISTED` remain unused. Deliberately out of scope.
- **Token cache** races remain limited by `volatile` only (no distributed cache); acceptable
  for the single-instance deployment.
- **429** is classified but there is no backoff (mutations intentionally not retried).
- Idempotency of re-assign / remove-absent is still not asserted by a dedicated test.
- H1 enabled-check adds one extra Keycloak call per mutation (accepted trade-off).

## 7) `git status --short`

```
 M src/main/java/com/sdigital/platform/common/exception/GlobalExceptionHandler.java
 M src/main/java/com/sdigital/platform/common/security/SecurityConfig.java
 M src/main/resources/application.yml
?? docs/architecture/keycloak-role-management-audit.md
?? docs/architecture/keycloak-role-management-implementation-plan.md
?? docs/architecture/keycloak-role-management-plan-review.md
?? docs/architecture/keycloak-role-management-qa-review.md
?? docs/architecture/keycloak-role-management-fixes.md
?? docs/keycloak-role-management-setup.md
?? etape_4c1_conception_agencies_functions.txt
?? src/main/java/com/sdigital/platform/common/security/SecurityConfigConstants.java
?? src/main/java/com/sdigital/platform/user/controller/UserRoleController.java
?? src/main/java/com/sdigital/platform/user/dto/AssignRoleRequest.java
?? src/main/java/com/sdigital/platform/user/dto/UserRoleDto.java
?? src/main/java/com/sdigital/platform/user/dto/UserRolesResponse.java
?? src/main/java/com/sdigital/platform/user/keycloak/
?? src/test/java/com/sdigital/platform/common/security/UserRoleAdminAuthorizationSecurityTest.java
?? src/test/java/com/sdigital/platform/user/controller/UserRoleControllerTest.java
?? src/test/java/com/sdigital/platform/user/keycloak/
```

Pre-existing untracked files (`SecurityConfigConstants.java`,
`etape_4c1_conception_agencies_functions.txt`) were left untouched. No `git add -A` used.

## 8) Conclusion — GO

- **H1**: fixed and verified by tests (service: mutation never called + `RoleChangeDeniedException`;
  controller: HTTP 403).
- **M2**: fixed and verified (timeout applied to the HTTP layer, demonstrated by test).
- **M3**: fixed and verified (mutations wrapped, network/timeout/5xx → 503, upstream 401/403 →
  500 integration error; no sensitive detail leaked; no retry).

Full suite green (304/304). No commit, no push. No change to shared Keycloak or PostgreSQL.
Migrations V1–V4 untouched.
