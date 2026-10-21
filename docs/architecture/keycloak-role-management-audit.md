# Keycloak Role Management Audit (READ-ONLY) — Étape 4E.1

Last commit: `b747cab feat(user): add agency and function assignments`  
Branch: `main`  
Date of audit: 2026-10-20  
Environment: Java 21, Spring Boot 3.4.1, PostgreSQL 16, Flyway, Keycloak 26.5.2

## 1) What exists (verified facts)

### Authentication / token validation
- **Resource server**: `spring-boot-starter-oauth2-resource-server`. JWT validation via `KeycloakJwtDecoderConfig` — builds `NimbusJwtDecoder` from JWKS at `<issuer>/protocol/openid-connect/certs` with issuer validation; fails closed if `KEYCLOAK_ISSUER_URI` not set (returns 401).
- **Role mapping**: `KeycloakRolesConverter` maps only `realm_access.roles` → authorities `ROLE_<role>`, and only trusted client roles from `resource_access.<clientId>.roles` → authorities `CLIENT_<clientId>_<role>` (config via `sdigital.security.keycloak.client-role-ids`, default `s-digital-api`). Does **not** map scopes/OAuth2 scopes, does not map unknown claims.
- **Identity resolution**: `KeycloakIdentityResolver` extracts only `sub` from JWT; `UserIdentityService` links local `User` by `keycloakId == sub` (no auto-provisioning, no email linking). `LocalUserActiveGuardFilter` enforces linked + ACTIVE local identity.

### Authorization / RBAC
- **RBAC rules** (in `SecurityConfig`): Users: GET list/byId requires `hasAnyRole("ADMIN","MANAGER")`; POST/PUT/DELETE/activate requires `hasRole("ADMIN")`. Agencies/Functions: same pattern (GET shared, writes+activate ADMIN only). Other `/api/v1/**` require authenticated; default deny.
- **Principal**: default JWT principal name is `sub`. No Keycloak Admin client/service present in codebase.

### User management API (current)
- **Endpoints** (`UserController`): `POST /api/v1/users`, `GET /api/v1/users`, `GET /api/v1/users/{id}`, `PUT /api/v1/users/{id}`, `DELETE /api/v1/users/{id}`, `POST /api/v1/users/{id}/activate`. Sortable props whitelist excludes `keycloakId`. Pagination/search by email/firstName/lastName/status.
- **DTOs**: `CreateUserRequest`/`UpdateUserRequest` include `email`, `firstName`, `lastName`, `agencyId`, `functionId` (optional). `UserResponse` includes `id`, `email`, `firstName`, `lastName`, `status`, `createdAt`, `updatedAt`, `agency`, `function` (embedded minimal DTOs) — **`keycloakId` not exposed**. `User` entity has `keycloakId` (not exposed).
- **Service**: `UserService` manages local users (create/update/deactivate/activate), validates agency/function ACTIVE on assignment (4D.1). No role assignment logic.

### Dependencies / Config
- `pom.xml`: `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` only. **No Keycloak Admin client / `keycloak-admin-client` dependency present.**
- `application.yml`: `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_CLIENT_ROLE_IDS` (default `s-digital-api`), `SDIGITAL_CORS_ALLOWED_ORIGINS`. No Keycloak Admin API/client config present.

## 2) Capability assessment (can/cannot)

| Action | Current state | Notes |
| --- | --- | --- |
| Consult Keycloak roles of a user | **No** | Only roles from the caller's JWT are mapped; no code fetches another user's roles from Keycloak. |
| Assign a realm role | **No** | No Admin client/service/API calls. |
| Remove a realm role | **No** | Same as above. |
| Manage client roles | **No** | Same as above. |
| View/manage via existing User API | **No** | User endpoints only manage local user profile/status/agency/function; no role fields. |

## 3) Risks
- **Privilege escalation / auto-elevation**: if any future endpoint calls Admin API without strict checks, risk of granting ADMIN. Must prevent self-assignment of ADMIN and enforce allowlist.
- **Excessive privileges**: service account would need broad permissions if mis-scoped; must use least privilege (specific realm/client roles).
- **Secret exposure**: client secret must never be in Git; use env vars/secret manager.
- **Inconsistency**: local `User` vs Keycloak roles can diverge; need clear source of truth and error handling.
- **Token leakage / audit**: all role changes must be audited (who, target, roles added/removed, outcome).
- **Confusion realm vs client roles**: mapping distinguishes them; admin operations must be explicit about scope (realm vs client).

## 4) Minimal secure architecture (recommendation, no implementation)
### Components
1. **Keycloak Admin client config**: add `keycloak-admin-client` dependency (or use REST). Configure via env vars: `KEYCLOAK_ADMIN_CLIENT_ID`, `KEYCLOAK_ADMIN_CLIENT_SECRET` (confidential client with service account), `KEYCLOAK_ADMIN_SERVER_URL`, `KEYCLOAK_REALM`. **No secrets in Git**.
2. **Service account client**: dedicated confidential backend client in Keycloak with minimal roles (e.g. `manage-users`, `view-users`). Enable service account.
3. **`KeycloakRoleAdminService`** (backend): methods `getUserRealmRoles(keycloakId)`, `assignRealmRole(keycloakId, role)`, `removeRealmRole(keycloakId, role)`, optionally client roles. Use Admin API, handle errors, log without tokens/secrets.
4. **Authorization**: only `ADMIN` (realm role) can call role-management endpoints. Enforce **protection against self-elevation** (cannot grant/revoke ADMIN to self). Also consider not allowing changes to certain system accounts.
5. **Allowlist**: explicit list of assignable roles (e.g. `MANAGER`, `USER`) — **never allow arbitrary role names**; ADMIN changes require extra care/explicit allowlist policy.
6. **Error handling**: map Keycloak errors to RFC7807 (404 if user not found, 409 on conflicts, 403 on forbidden operations, 502/503 on Keycloak unavailable) without leaking details.

### Configuration (env only)
```properties
keycloak.admin.server-url=${KEYCLOAK_ADMIN_SERVER_URL:}
keycloak.admin.realm=${KEYCLOAK_ADMIN_REALM:}
keycloak.admin.client-id=${KEYCLOAK_ADMIN_CLIENT_ID:}
keycloak.admin.client-secret=${KEYCLOAK_ADMIN_CLIENT_SECRET:}
sdigital.security.keycloak.role-allowlist=MANAGER,USER
```

## 5) Proposed endpoints & DTOs (no implementation)
### Endpoints (under `/api/v1/users/{id}/roles`)
- `GET /api/v1/users/{id}/roles` — list realm roles (and optionally client roles) for the user. Admin only.
- `POST /api/v1/users/{id}/roles` — assign roles (body with role names). Admin only; enforce allowlist + self-protection.
- `DELETE /api/v1/users/{id}/roles` — remove roles. Admin only; enforce allowlist + self-protection.

### DTOs
```java
public record UserRoleDto(String name, String type) {} // type: REALM|CLIENT
public record UserRolesResponse(List<UserRoleDto> realmRoles, List<UserRoleDto> clientRoles) {}
public record AssignRolesRequest(List<String> roles) {}
public record RemoveRolesRequest(List<String> roles) {}
```
Notes: do not expose `keycloakId` in public responses unless necessary; prefer user UUID. All operations require ADMIN, validate target user exists locally and linked to Keycloak.

## 6) Business decisions to confirm with El Mehdi
- **Scope**: manage realm roles only, or also client roles? Current mapping trusts specific client roles; need confirmation.
- **Assignable roles**: confirm allowlist (e.g. only `MANAGER` assignable by ADMIN? Or `USER` too?) and whether ADMIN can be granted/revoked via API.
- **Self-protection**: should ADMIN be prevented from removing their own ADMIN role / modifying self?
- **Source of truth**: treat Keycloak as source of truth for roles; should local DB store roles (no, currently not stored)?
- **Error semantics**: when Keycloak unavailable, fail closed (503) vs degrade?
- **Audit**: required audit fields/events?
- **System users**: protection for specific accounts?

## Conclusion
- **Facts verified**: JWT resource server + role mapping present; local identity resolution present; User API manages profile/status/agency/function only. **No Keycloak Admin integration exists**.
- **Capability**: cannot currently consult/assign/remove Keycloak roles.
- **Recommendation**: implement dedicated service account + minimal allowlist + self-protection + audit, behind ADMIN-only endpoints, with no secrets in Git.

**No code, PostgreSQL, or Keycloak modified.** Git status unchanged (only untracked files remain).