# Keycloak Role Management — QA & Security Review (READ-ONLY) — Étape 4E.5

Last commit: `b747cab feat(user): add agency and function assignments`  
Branch: `main`  
Status: READ-ONLY independent review of the code actually present (not the 4E.4 report).

## 1) Files examined
**Production (4E.4):**
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakAdminProperties.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakAdminClient.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakAdminConfig.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminService.java`
- `src/main/java/com/sdigital/platform/user/keycloak/RoleChangeAuditLogger.java`
- `src/main/java/com/sdigital/platform/user/keycloak/exception/*.java` (5 exceptions)
- `src/main/java/com/sdigital/platform/user/dto/{UserRoleDto,UserRolesResponse,AssignRoleRequest}.java`
- `src/main/java/com/sdigital/platform/user/controller/UserRoleController.java`
- `src/main/java/com/sdigital/platform/common/exception/GlobalExceptionHandler.java` (modified)
- `src/main/java/com/sdigital/platform/common/security/SecurityConfig.java` (modified)
- `src/main/resources/application.yml` (modified)

**Tests (4E.4):**
- `src/test/java/com/sdigital/platform/user/keycloak/KeycloakAdminClientTest.java` (7)
- `src/test/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminServiceTest.java` (14)
- `src/test/java/com/sdigital/platform/user/controller/UserRoleControllerTest.java` (9)
- `src/test/java/com/sdigital/platform/common/security/UserRoleAdminAuthorizationSecurityTest.java` (4)

## 2) Controls verified (with evidence)

### 2.1 HTTP authorization
- `SecurityConfig` line 95-96: `requestMatchers("/api/v1/users/{id}/roles", "/api/v1/users/{id}/roles/**").hasRole("ADMIN")`, declared **before** the generic `/api/v1/**` authenticated rule. Covers `GET`, `POST`, `DELETE .../{roleName}`.
- `UserRoleAdminAuthorizationSecurityTest` proves ADMIN allowed, MANAGER denied, USER denied, missing JWT → 401.
- Actor identity comes exclusively from the validated JWT via `KeycloakIdentityResolver.getCurrentKeycloakSub()` (`KeycloakRoleAdminService.resolveActor`, line 205). Self-modification is blocked in `prepareMutation` (line 145-150) comparing actor sub to target `keycloakId`, **before** any Keycloak mutation call.

### 2.2 Keycloak Admin client
- client_credentials token obtained from `/realms/{realm}/protocol/openid-connect/token`; token cached in a `volatile CachedToken` with refresh 30s before expiry (lines 159-180).
- The client secret is only placed in the token form (line 186); it is never logged.
- **Not verified:** explicit timeouts are **not** applied (see anomalies).
- 404 on role lookup handled via `onStatus(...404...)` returning null → `false` (lines 141-150).
- **Not verified:** specific handling of 401/403/429/5xx from Admin endpoints; these propagate as runtime exceptions.

### 2.3 Allowlist & roles
- `KeycloakAdminProperties.allowlistAsSet()` trims, filters blanks and de-duplicates via `LinkedHashSet`; `setAllowlist(String csv)` splits/trims. Spaces and duplicates are neutralized.
- `ensureAllowlistConfigured()` fails closed when empty; `ensureRoleIsManageable()` rejects ADMIN (case-insensitive) and any role not in the allowlist.
- Client roles are never referenced: only `/role-mappings/realm` endpoints are used (no `/role-mappings/clients/...`).

### 2.4 User consistency
- Only `User.keycloakId` is used (`resolveTarget`, line 183-187); no email lookup, no auto-provisioning anywhere.
- Local missing → `UserNotFoundException` (404); unlinked → `UserNotLinkedToKeycloakException` (422); Keycloak user missing → `KeycloakUserNotFoundException` (404).

### 2.5 Exception mapping (`GlobalExceptionHandler`)
- 422, 404, 403, 400, 503 mapped with generic details; `KeycloakUnavailableException` detail never exposes the cause.

### 2.6 Audit
- `RoleChangeAuditLogger.roleChanged(...)` logs act lines on logger `SDIGITAL_AUDIT`; no token/secret/body. Success is logged only after the mutation returns true.

## 3) Anomalies

### CRITICAL
None.

### HIGH
- **H1 — Disabled Keycloak accounts are never refused.** `resolveTarget(targetUserId, false)` is called with `requireEnabled=false` in **all** paths (`getRoles` line 83, `prepareMutation` line 141). The `isUserEnabled` check (lines 194-199) is therefore dead code, and `REASON_KEYCLOAK_DISABLED` / the "disabled" `RoleChangeDeniedException` are unreachable. The 4E.4 spec explicitly requires refusing role changes on disabled accounts, and the 4E.4 report claimed this behaviour. Evidence: no test exercises a disabled Keycloak account against the service.

### MEDIUM
- **M2 — Configured timeouts are not applied.** `KeycloakAdminProperties.connectTimeout/readTimeout` exist, but `KeycloakAdminClient` builds `RestClient` without a request factory carrying those timeouts (`KeycloakAdminConfig` passes the shared builder unchanged). Calls currently use defaults; a hung Keycloak could block request threads longer than intended.
- **M3 — Mutations are not wrapped in `guardedKeycloakCall`.** `assignRole` (line 101) and `removeRole` (line 119) call the client directly. A network/timeout/5xx error during the mutation surfaces as an unclassified `RuntimeException` → generic 500, not the intended 503. Only the read path and `findUserId` are wrapped. Inconsistent error semantics between reads and mutations.
- **M4 — No handling of 401/403/429/5xx from Admin endpoints.** A 401/403 (misconfigured service account) or 429 (rate limit) becomes a generic failure; 429 in particular is not distinguished and there is no backoff (retry absence is intentional for mutations, but 429 handling is unspecified).

### LOW
- **L5 — `GET /roles` filtering is lossy and indistinguishable.** `getRoles` filters Keycloak roles by the allowlist, so roles present in Keycloak but outside the allowlist (e.g. ADMIN or non-managed roles) are silently hidden. The response cannot distinguish "no roles" from "roles exist but are not manageable". This may be acceptable (security by minimization) but is undocumented in the API contract.
- **L6 — Audit lacks an explicit timestamp field and only logs one denial reason.** `roleChanged`/`roleChangeDenied` rely on the log framework's timestamp (no `timestamp=` field), and only `SELF_MODIFICATION_DENIED` is logged; other denials (ADMIN role, not allowlisted, disabled) are not audited.
- **L7 — `REASON_RESERVED_ROLE` / `REASON_NOT_ALLOWLISTED` constants are declared but unused.**
- **L8 — No validation of role-name characters** beyond `@NotBlank`/`@Size` (e.g. control characters) before being placed in a URL path segment. RestClient encodes path variables, mitigating injection, but strict allowlist membership already constrains names.
- **L9 — Role lookup for remove/assign resolves the allowlist twice** (`ensureRoleIsManageable` and again in `getRoles`); harmless but redundant.

## 4) Tests executed and results
- Targeted (Java 21): `KeycloakAdminClientTest, KeycloakRoleAdminServiceTest, UserRoleControllerTest, UserRoleAdminAuthorizationSecurityTest, UserRoleAuthorizationSecurityTest` → **51 tests, 0 failures, 0 errors, BUILD SUCCESS**.
- Full suite `mvn.cmd -B test` (Java 21): first run **247 tests / 4 errors** — all 4 were Testcontainers `postgres:16` failures ("Could not find a valid Docker environment"), unrelated to 4E.4. After Docker was confirmed up (`docker info` OK), rerun → **288 tests, 0 failures, 0 errors, BUILD SUCCESS**.

## 5) Residual risks & uncovered scenarios
- Disabled Keycloak account path (H1) — **untested and non-functional**.
- Timeout behaviour (M2) and 429/short-circuit (M4) — no tests.
- Mutation-time Keycloak failure maps to 500, not 503 (M3) — no test.
- Assigning an already-present role and removing an absent role: `addRealmRole`/`removeRealmRole` return success based only on the role existing in the realm, so these are effectively idempotent from the API's view (Keycloak itself is idempotent for these mappings); no explicit test asserts this.
- Audit persistence/integrity/access (log-only, rolled with normal logs) — not an immutable trail.
- No concurrency test (parallel assign/remove, token cache races are limited by `volatile` only).

## 6) Conclusion — GO / NO-GO
**NO-GO for commit as-is.**

Justification: **H1** means an explicit security/business requirement from 4E.4 (refuse role changes for disabled Keycloak accounts) is not implemented — the code path is dead. **M2** (timeouts not applied) and **M3** (mutations return 500 instead of 503 on Keycloak failure) also contradict stated behaviour. These should be fixed and covered by tests before committing. **L5–L9** are non-blocking improvements to document.

Recommended before commit (do not apply during this QA step):
1. Call `resolveTarget(targetUserId, true)` in the mutation path (or add an explicit enabled check) and add a service + controller test for the disabled-account case (expect 403).
2. Apply configured connect/read timeouts to the `RestClient` (e.g. a `ClientHttpRequestFactory`) and test it.
3. Wrap `addRealmRole`/`removeRealmRole` in `guardedKeycloakCall` so failures map to 503, and add a mutation-failure test.
4. Document the `GET /roles` allowlist filtering semantics; remove unused constants; consider logging all denial reasons.

**No code, config, migration, PostgreSQL or Keycloak was modified during this review.**