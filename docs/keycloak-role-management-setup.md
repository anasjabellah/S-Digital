# Keycloak Role Management — Backend Configuration (manual steps)

This document lists the manual Keycloak and environment configuration required before the
`/api/v1/users/{id}/roles` endpoints can work. The backend code performs **no** Keycloak
provisioning: no client, role or account is created automatically.

## 1. Create a dedicated confidential client (service account)
In the target realm (e.g. `s-digital`):

1. Clients -> Create client.
   - Client type: **OpenID Connect**, Client authentication: **ON** (confidential).
   - Client ID: e.g. `s-digital-backend-admin` (must match `KEYCLOAK_ADMIN_CLIENT_ID`).
2. Enable **Service account roles** (rôles du compte de service) for this client.
3. Assign the **minimum** permissions to the service account (client `realm-management`):
   - `view-users`  (look up and read users)
   - `manage-users` (list/add/remove users' realm role mappings)
   Do **not** grant `realm-admin`, `manage-realm` or `manage-clients` unless a documented
   need appears later.

## 2. Create the assignable realm roles
Create the realm roles that the API may manage (for example `MANAGER`, `USER`).
**Never** include `ADMIN` in the allowlist: ADMIN assignment/removal is refused by business
rule regardless of configuration.

## 3. Configure environment variables
No secret is stored in Git. Provide these at runtime (secret manager / env):

| Variable | Description | Example |
| --- | --- | --- |
| `KEYCLOAK_ADMIN_SERVER_URL` | Base URL of Keycloak | `http://localhost:8180` |
| `KEYCLOAK_ADMIN_REALM` | Realm owning users and the service account | `s-digital` |
| `KEYCLOAK_ADMIN_CLIENT_ID` | Confidential client id | `s-digital-backend-admin` |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Client secret (never logged) | *(secret)* |
| `SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST` | Comma-separated assignable realm roles | `MANAGER,USER` |

When the allowlist is empty or the integration is not configured, all role **mutations**
fail closed (403/503) and the read endpoint returns no manageable roles.

## 4. Behaviour summary
- Realm roles only; client roles are never touched.
- `ADMIN` cannot be assigned or removed through the API.
- An administrator cannot modify their own roles (JWT `sub` == target `keycloakId`).
- Users must already be linked (`app_user.keycloak_id`); no email lookup, no auto-provisioning.
- Disabled Keycloak accounts are refused (403).
- Timeouts are explicit; mutations are never retried automatically.
- Successful mutations are logged on the `SDIGITAL_AUDIT` logger (no tokens/secrets).