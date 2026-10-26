# Keycloak Role Management — Targeted Hardening (4E.8)

Base commit: `8fcb2c1 feat(security): manage Keycloak realm roles securely`.
Branch: `main`. Scope: close the residual, non-blocking risks of 4E.4–4E.6 without
widening the functional surface. No commit, no push, no change to shared Keycloak,
no change to PostgreSQL, migrations V1–V4 untouched, no new dependency.

## 1) Idempotency of mutations

### Proven behaviour (not assumed)
Verified against the running Keycloak 26 (realm `s-digital`, user `testuser`) via the
Admin REST API:

| Operation | HTTP status |
|-----------|-------------|
| `POST /users/{id}/role-mappings/realm` (first assign) | 204 |
| `POST /users/{id}/role-mappings/realm` (role already present) | 204 |
| `DELETE /users/{id}/role-mappings/realm` (first remove) | 204 |
| `DELETE /users/{id}/role-mappings/realm` (role absent) | 204 |

So Keycloak's realm role-mapping endpoints are idempotent: adding an already-assigned
role and removing an absent role both succeed with 2xx. The test user was restored to
its original state (no realm roles mapped) after the probe. No shared data was left
modified.

### Retained HTTP contract (unchanged)
- `POST .../roles` and `DELETE .../roles/{roleName}` return **200** with the updated
  `UserRolesResponse` on success, including when the role was already assigned / already
  absent. This was the pre-existing behaviour; it is now proven and explicitly documented
  in the OpenAPI descriptions. No silent contract change.
- The boolean returned by `KeycloakAdminClient.addRealmRole/removeRealmRole` means
  **"the role name exists in the Keycloak realm"**, not "the mapping changed". A `false`
  means the role does not exist in Keycloak → `RoleNotAllowedException` → **400**. This
  subtlety is now documented so it is not mistaken for an idempotency signal.

### Tests
`KeycloakRoleAdminServiceTest`:
- `assignRoleIsIdempotentWhenRoleAlreadyPresent` — 200-equivalent, exactly one remote call.
- `removeRoleIsIdempotentWhenRoleAbsent` — empty result, exactly one remote call.
- `mutationIsNeverRetriedOnFailure` — on a network failure the remote mutation is invoked
  exactly once (no retry), and the error maps to 503.
- Anti-self-modification and anti-ADMIN protections remain covered by the existing tests
  (`assignRoleRejectsAdminRole`, `removeRoleRejectsAdminRole`,
  `assignRoleRejectsSelfModification`), all still green.

## 2) Audit of refusals

`RoleChangeAuditLogger` now documents itself explicitly as **not** a persistent,
tamper-proof audit journal (ordinary rotated application logs), and lists the reason
codes. A single private helper `deny(...)` guarantees each refusal is logged **exactly
once** (no duplicate logging) without any secret, token or raw Keycloak payload.

Reason codes now emitted on the `SDIGITAL_AUDIT` logger:

| Refusal | reason code | Where |
|---------|-------------|-------|
| Self-modification | `SELF_MODIFICATION_DENIED` | `prepareMutation` |
| Reserved ADMIN role | `RESERVED_ROLE_DENIED` | `prepareMutation` (guard) |
| Role not in allowlist | `NOT_ALLOWLISTED` | `prepareMutation` (guard + empty-allowlist) |
| Target Keycloak account disabled | `KEYCLOAK_ACCOUNT_DISABLED` | `prepareMutation` (resolve) |
| Local user not linked | `USER_NOT_LINKED_TO_KEYCLOAK` | `prepareMutation` (resolve) |
| Keycloak user not found | `KEYCLOAK_USER_NOT_FOUND` | `prepareMutation` (resolve) |

Deliberately **not** audited as refusals:
- local user not found (plain 404, not a role-change refusal);
- integration unavailable / misconfigured (`KeycloakUnavailableException` /
  `KeycloakIntegrationException`) — operational failures already logged at WARN/ERROR by
  the service; they are not policy refusals.

Only identifiers, role name and the coarse reason code are logged — never the client
secret, tokens, Keycloak response bodies or unnecessary personal data.

### Tests
`KeycloakRoleAdminServiceTest`: `assignRoleAuditsAdminRoleRefusal`,
`assignRoleAuditsNotAllowlistedRefusal`, `assignRoleAuditsDisabledAccountRefusal`,
`removeRoleAuditsUnlinkedUserRefusal`, `removeRoleAuditsKeycloakUserNotFoundRefusal`,
`successfulMutationAuditsChangeAndNoDenial` (verifies a success logs `roleChanged` and
**no** denial). Each denial test asserts `times(1)` so duplicates would fail.

## 3) GET `/api/v1/users/{id}/roles` contract

`UserRolesResponse` gains one **additive, backward-compatible** field:

```json
{
  "roles": [ { "name": "MANAGER" } ],
  "hasUnmanageableRoles": true
}
```

State mapping (no excluded role name is ever disclosed):

| State | `roles` | `hasUnmanageableRoles` |
|-------|---------|------------------------|
| No realm role at all | `[]` | `false` |
| Only manageable roles assigned | non-empty | `false` |
| Also holds roles outside the allowlist (e.g. ADMIN) | (manageable subset only) | `true` |

Compatibility: the existing `roles` field keeps its name, type and semantics, so current
clients are unaffected. A single-argument convenience constructor preserves the old
construction shape. The OpenAPI description of the GET operation was updated to mention
the flag. No excluded role name (ADMIN, others) is ever returned.

### Tests
`KeycloakRoleAdminServiceTest`: `getRolesReportsNoRolesWhenNoneAssigned`,
`getRolesReportsManageableRolesWithoutUnmanageableFlag`,
`getRolesSignalsUnmanageableRolesWithoutLeakingTheirNames` (asserts only `MANAGER` is
returned while the flag is `true`).
`UserRoleControllerTest`: `getRolesReturns200WithHasUnmanageableRolesFlagFalse`,
`getRolesReturns200WithHasUnmanageableRolesFlagTrueWithoutLeakingNames` (asserts the
payload contains exactly one role and the flag).

## 4) Tests executed

### Targeted (Java 21)
`KeycloakRoleAdminServiceTest, UserRoleControllerTest, KeycloakAdminClientTest,
KeycloakAdminConfigTest, UserRoleAdminAuthorizationSecurityTest`
→ **64 tests, 0 failures, 0 errors, BUILD SUCCESS**.

### Full suite `mvn.cmd -B test` (Java 21)
→ **318 tests, 0 failures, 0 errors, BUILD SUCCESS** (304 before this step; +14).
Docker was reachable on the first run, so no Testcontainers retry was needed.

## 5) Files modified

- `src/main/java/com/sdigital/platform/user/dto/UserRolesResponse.java`
- `src/main/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminService.java`
- `src/main/java/com/sdigital/platform/user/keycloak/RoleChangeAuditLogger.java`
- `src/main/java/com/sdigital/platform/user/controller/UserRoleController.java`
- `src/test/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminServiceTest.java`
- `src/test/java/com/sdigital/platform/user/controller/UserRoleControllerTest.java`

No new files. No migrations, no properties, no security config changed.

## 6) Residual risks & decisions still needed

- **Log-based audit only.** Not persistent or immutable; a durable audit trail
  (retention, access control, integrity) remains a separate infrastructure decision.
- **`hasUnmanageableRoles` is a summary, not a count or list.** If the business later
  needs to know *how many* or *which category* of non-manageable roles exist, that
  requires a deliberate contract extension.
- **Idempotency contract is documented, not code-enforced.** The API deliberately keeps
  returning 200 for a no-op assign/remove (matching Keycloak); if the business wants 409
  for a true change vs. 200 for a no-op, that is a contract decision not taken here.
- **`false` from the client means "role absent in Keycloak"**, not "no change"; kept as-is
  to avoid changing HTTP semantics.
- Still open from earlier steps: whether ADMIN should ever be manageable, whether a service
  account should be excluded from self-modification, and audit retention/access policy
  (business decisions with El Mehdi).

## 7) `git status --short`

```
 M src/main/java/com/sdigital/platform/user/controller/UserRoleController.java
 M src/main/java/com/sdigital/platform/user/dto/UserRolesResponse.java
 M src/main/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminService.java
 M src/main/java/com/sdigital/platform/user/keycloak/RoleChangeAuditLogger.java
 M src/test/java/com/sdigital/platform/user/controller/UserRoleControllerTest.java
 M src/test/java/com/sdigital/platform/user/keycloak/KeycloakRoleAdminServiceTest.java
?? etape_4c1_conception_agencies_functions.txt
?? src/main/java/com/sdigital/platform/common/security/SecurityConfigConstants.java
```

The two untracked files are pre-existing and were left untouched. No `git add` was run,
no commit, no push. No shared Keycloak or PostgreSQL data was modified.
