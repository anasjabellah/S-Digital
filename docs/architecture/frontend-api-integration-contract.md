# Frontend API Integration Contract — S-Digital Backend

> **Statut :** contrat d'intégration à jour, établi par lecture du **code source**, de la
> **spécification OpenAPI générée** et des **tests automatisés**.
>
> **Base de vérification :**
> - Branche `main`, HEAD `279591a`, plus les modifications non commitées de la phase
>   « profil courant / notifications » (préservées).
> - Build courant démarré pour vérification sur `http://localhost:8081` (le port 8080 était
>   déjà occupé par une instance locale plus ancienne) ; le port par défaut reste 8080.
> - OpenAPI 3.1 : `GET /v3/api-docs` et `GET /v3/api-docs.yaml`.
> - Code : controllers, services, DTO, `SecurityConfig`, `GlobalExceptionHandler`.
>
> Les endpoints réellement appelés en runtime sont listés explicitement au §8. Aucun
> comportement 200 métier n'est déclaré « vérifié en runtime » sans appel réel.

---

## 1. Vue d'ensemble

- **Style :** API REST versionnée sous `/api/v1`, JSON, erreurs **RFC 7807 `ProblemDetail`**
  (sauf le 401, voir §2).
- **Sécurité :** OAuth2 resource server, JWT **Keycloak** (`Authorization: Bearer <token>`).
- **Identifiants :** UUID générés côté serveur, sérialisés en chaîne.
- **Dates :** `Instant` ISO-8601 UTC (ex. `2026-01-01T12:00:00Z`).
- **Pagination :** `Page<T>` Spring ; `page` 0-based, `size` défaut 20 / max 100,
  `sort=propriété,asc|desc`.
- **Rôles :** realm roles Keycloak mappés en `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_USER`.
- **Garde locale :** toute route `/api/v1/**` exige en plus un utilisateur local **lié +
  ACTIVE** (`app_user.keycloak_id` = claim JWT `sub`), sinon **403**.
- **Mises à jour :** `PUT` = remplacement complet. Seul `PATCH` existant :
  `PATCH /api/v1/notifications/{id}/read`.
- **Suppressions :** désactivation logique (`status → INACTIVE`) ; aucune ligne supprimée.

### Convention d'en-têtes

| En-tête | Valeur |
|---|---|
| `Authorization` | `Bearer <JWT Keycloak>` (requis sauf endpoints publics) |
| `Content-Type` | `application/json` (requêtes avec body) |
| `Accept` | `application/json` (ou type binaire pour les rapports) |

### Endpoints publics (sans token)

| Méthode | Chemin |
|---|---|
| GET | `/actuator/health`, `/actuator/health/**` |
| GET | `/v3/api-docs`, `/v3/api-docs.yaml`, `/v3/api-docs/**` |
| GET | `/swagger-ui.html`, `/swagger-ui/**` |
| OPTIONS | Toutes les routes (preflight CORS) |

> `/actuator/info` est exposé par la configuration Actuator mais **n'est pas public** :
> il est refusé par la politique de sécurité.

---

## 2. Authentification & autorisation

### 2.1 Réponses 401 et 403

- **401 Unauthorized** : token absent, expiré, signature invalide, ou `KEYCLOAK_ISSUER_URI`
  non configuré côté serveur.
  **Corps vide**, en-tête `WWW-Authenticate: Bearer`. Ce n'est **pas** un `ProblemDetail`.
  → Angular doit déclencher une (re)authentification Keycloak.

- **403 Forbidden** : token valide mais (a) rôle insuffisant, ou (b) identité locale non liée
  ou `INACTIVE`. Corps `ProblemDetail` :
  ```json
  { "type": "https://s-digital.io/errors/forbidden", "title": "Forbidden",
    "status": 403, "detail": "Access denied" }
  ```
  Aucun JWT, rôle, identifiant ou détail interne n'y est exposé.
  → Angular affiche « accès refusé » sans tenter de déduire le rôle manquant.

### 2.2 Matrice des rôles (vérifiée dans `SecurityConfig`)

| Ressource | USER | MANAGER | ADMIN |
|---|:--:|:--:|:--:|
| `GET /api/v1/users/me`, `PUT /api/v1/users/me` | ✔ | ✔ | ✔ |
| `GET /api/v1/users`, `GET /api/v1/users/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/users*`, `POST /{id}/activate` | ✘ | ✘ | ✔ |
| `/api/v1/users/{id}/roles` (GET/POST/DELETE) | ✘ | ✘ | ✔ |
| `GET /api/v1/agencies`, `/{id}` ; `GET /api/v1/functions`, `/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/agencies*`, `/functions*`, `/{id}/activate` | ✘ | ✘ | ✔ |
| `GET /api/v1/dashboard/summary` | ✘ | ✔ | ✔ |
| `GET /api/v1/reports` | ✘ | ✘ | ✔ |
| `GET /api/v1/audit-logs` | ✘ | ✘ | ✔ |
| Notifications de l'utilisateur courant (`GET`, `GET /{id}`, `/unread`, `/read`, `/read/all`) | ✔ | ✔ | ✔ |
| `POST /api/v1/notifications` | ✘ | ✘ | ✔ |
| `/api/v1/email-templates*` | ✘ | ✘ | ✔ |

> `/me` et les routes de lecture des notifications exigent **authentification** mais **aucun
> rôle spécifique**. La garde « lié + ACTIVE » s'applique toujours.

---

## 3. DTOs à utiliser (Frontend / TypeScript)

### Users

```ts
type UserStatus = 'ACTIVE' | 'INACTIVE';

interface UserAgencyDto  { id: string; code: string; name: string; status: 'ACTIVE'|'INACTIVE'; }
interface UserFunctionDto{ id: string; code: string; name: string; status: 'ACTIVE'|'INACTIVE'; }

interface UserResponse {
  id: string; email: string; firstName: string; lastName: string;
  status: UserStatus;
  agency: UserAgencyDto | null;
  function: UserFunctionDto | null;
  createdAt: string; updatedAt: string;
} // keycloakId volontairement absent

interface CreateUserRequest {
  email: string; firstName: string; lastName: string;
  agencyId?: string | null; functionId?: string | null;
}
interface UpdateUserRequest {
  email: string; firstName: string; lastName: string;
  agencyId?: string | null; functionId?: string | null;
}
interface UpdateCurrentUserRequest {
  firstName: string;   // requis, max 100
  lastName: string;    // requis, max 100
}
```

### Agencies / Functions

```ts
interface AgencyResponse {
  id: string; code: string; name: string; status: 'ACTIVE'|'INACTIVE';
  createdAt: string; updatedAt: string;
}
interface CreateAgencyRequest { code: string; name: string; }
interface UpdateAgencyRequest { code: string; name: string; }

interface FunctionResponse {
  id: string; code: string; name: string; status: 'ACTIVE'|'INACTIVE';
  createdAt: string; updatedAt: string;
}
interface CreateFunctionRequest { code: string; name: string; }
interface UpdateFunctionRequest { code: string; name: string; }
```

### Notifications

```ts
interface NotificationResponse {
  id: string;
  userId: string;
  title: string;
  message: string;
  type: string;                 // ex. INFO, WARNING, ASSIGNMENT
  read: boolean;
  createdAt: string;
  readAt: string | null;
  link: string | null;
  metadata: Record<string, unknown> | null;
}

interface CreateNotificationRequest {
  recipientUserId: string;      // UUID d'un utilisateur local existant
  title: string;                // max 200
  message: string;              // max 1000
  type: string;                 // max 50
  link?: string | null;         // max 500
  metadata?: Record<string, unknown> | null;
}
```

### Email templates

```ts
type EmailTemplateStatus = 'ACTIVE' | 'INACTIVE';

interface EmailTemplateResponse {
  id: string;
  code: string;
  name: string;
  subject: string;
  body: string;
  locale: string;
  variables: string[];
  status: EmailTemplateStatus;
  createdAt: string; updatedAt: string;
}

interface CreateEmailTemplateRequest {
  code: string; name: string; subject: string; body: string;
  locale: string;               // ex. fr, fr-FR
  variables: string[];          // placeholders {{...}} déclarés
}
interface UpdateEmailTemplateRequest extends CreateEmailTemplateRequest {}
```

### Dashboard

```ts
interface EntityStatusStatistics { total: number; active: number; inactive: number; }

interface DashboardSummaryResponse {
  users: EntityStatusStatistics;
  agencies: EntityStatusStatistics;
  functions: EntityStatusStatistics;
  generatedAt: string;
}
```

### Audit

```ts
type AuditOutcome = 'SUCCESS' | 'DENIED' | 'FAILURE' | 'NO_CHANGE';

interface AuditLogResponse {
  id: string;
  occurredAt: string;
  actorUserId: string | null;
  action: string;               // ex. USER_UPDATED
  entityType: string;           // ex. USER, NOTIFICATION
  entityId: string | null;
  outcome: AuditOutcome;
  details: Record<string, unknown> | null;
}
```

### Keycloak realm roles

```ts
interface UserRoleDto { name: string; }         // nom de rôle realm uniquement
interface UserRolesResponse {
  roles: UserRoleDto[];                          // rôles gérés (allowlist) uniquement
  hasUnmanageableRoles: boolean;                 // rôles hors allowlist présents (noms jamais révélés)
}
interface AssignRoleRequest { roleName: string; }
```

### Pagination (Spring `Page<T>`)

```ts
interface Page<T> {
  content: T[];
  totalElements: number; totalPages: number; number: number; size: number;
  first: boolean; last: boolean; numberOfElements: number; empty: boolean;
  pageable?: {
    pageNumber: number; pageSize: number; offset: number;
    paged: boolean; unpaged: boolean;
    sort: { sorted: boolean; unsorted: boolean; empty: boolean };
  };
  sort?: { sorted: boolean; unsorted: boolean; empty: boolean };
}
```

---

## 4. Erreurs HTTP et traitement Angular

Format commun : `ProblemDetail` (`type`, `title`, `status`, `detail`), + `errors[]` pour la
validation. **Exception : 401 = corps vide.**

| Code | Cas | Corps / `detail` | Traitement Angular |
|---|---|---|---|
| **400** | body invalide (`@Valid`), JSON illisible, UUID malformé, `sort`/enum invalide | `detail` générique ; `errors:[{field,message}]` pour la validation | Afficher les erreurs de champ ; corriger la requête |
| **401** | token absent/expiré/invalide, `KEYCLOAK_ISSUER_URI` absent | **corps vide**, `WWW-Authenticate: Bearer` | Re-login Keycloak |
| **403** | rôle insuffisant OU identité locale non liée/INACTIVE | `detail: "Access denied"` | Message « accès refusé » |
| **404** | ressource absente ; notification d'un autre utilisateur | `detail` du type `"User not found"`, `"Notification not found"`, etc. | Écran « introuvable » |
| **409** | email/code déjà utilisé ; email local déjà pris ou identité Keycloak existante (provisioning) | `"Email already in use"`, `"Agency code already in use"`, `"Function code already in use"`, `"Email template code already in use"`, `"A Keycloak account already exists for this email"` | Message de conflit |
| **422** | utilisateur local non lié à Keycloak (routes rôles) | `"User is not linked to a Keycloak account"` | Message « compte non lié » |
| **500** | erreur interne, intégration Keycloak admin mal configurée, échec de persistance locale du provisioning | `"An unexpected error occurred"` | Message générique |
| **503** | Keycloak indisponible ou non configuré (provisioning, routes rôles) | `"Keycloak is temporarily unavailable"` | Réessayer plus tard |

Types RFC 7807 utilisés : `https://s-digital.io/errors/{validation|bad-request|not-found|conflict|forbidden|unprocessable|service-unavailable|internal}`.

---

## 5. Inventaire des endpoints par module

> Légende « Vérifié » :
> - **Spec** = présent et documenté dans OpenAPI généré.
> - **Code** = comportement lu dans controller/service/DTO.
> - **Testé** = couvert par des tests automatisés du dépôt.
> - **Runtime** = réellement appelé sur l'instance locale (voir §8).

### 5.1 Users — `/api/v1/users`

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| POST | `/api/v1/users` | ADMIN | 201 + `Location` + `UserResponse` (ligne locale seule, `keycloak_id` NULL) | Spec/Code/Testé |
| POST | `/api/v1/users/provision` | ADMIN | 201 + `Location` + `UserResponse` (crée et lie l'identité Keycloak) | Spec/Code/Testé |
| GET | `/api/v1/users` | ADMIN, MANAGER | 200 + `Page<UserResponse>` | Spec/Code/Testé/Runtime(401) |
| GET | `/api/v1/users/{id}` | ADMIN, MANAGER | 200 + `UserResponse` | Spec/Code/Testé |
| PUT | `/api/v1/users/{id}` | ADMIN | 200 + `UserResponse` | Spec/Code/Testé |
| DELETE | `/api/v1/users/{id}` | ADMIN | 204 (sans corps) | Spec/Code/Testé |
| POST | `/api/v1/users/{id}/activate` | ADMIN | 200 + `UserResponse` | Spec/Code/Testé |
| GET | `/api/v1/users/me` | authentifié (tout rôle) | 200 + `UserResponse` | Spec/Code/Testé/Runtime(401, 403) |
| PUT | `/api/v1/users/me` | authentifié (tout rôle) | 200 + `UserResponse` | Spec/Code/Testé |

**Query params de `GET /api/v1/users` :** `page`, `size`, `sort`, `search` (partiel insensible
à la casse sur `email`/`firstName`/`lastName`), `status` (`ACTIVE`|`INACTIVE`).
`sort` autorisé : `id, email, firstName, lastName, status, createdAt, updatedAt` (sinon 400).
Défaut : `createdAt,desc`.

**Validations :**
- `email` `@NotBlank @Email @Size(max=320)`, normalisé trim + minuscules.
- `firstName`/`lastName` `@NotBlank @Size(max=100)`.
- `agencyId`/`functionId` optionnels mais doivent référencer une entité **ACTIVE** (sinon 404
  `"Agency not found"` / `"Function not found"`).

**`PUT /api/v1/users/me` (profil courant) :**
- Body strictement limité à `firstName` et `lastName` (les deux requis).
- L'utilisateur local est résolu **uniquement** depuis le `sub` du JWT.
- `email`, `status`, `agency`, `function` et le lien Keycloak ne sont **jamais** modifiés.
- Un changement effectif est audité (`USER_PROFILE_UPDATED`).
- Mêmes réponses d'erreur que `GET /me` : 401, 403, 400.

**Exemple requête (création)**
```
POST /api/v1/users
{ "email": "ada.lovelace@example.com", "firstName": "Ada", "lastName": "Lovelace",
  "agencyId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "functionId": null }
```
**Exemple réponse (201)**
```json
{ "id": "c1a2...", "email": "ada.lovelace@example.com", "firstName": "Ada", "lastName": "Lovelace",
  "status": "ACTIVE", "agency": { "id": "3fa85...", "code": "AG-PARIS-01", "name": "Agency Paris 01", "status": "ACTIVE" },
  "function": null, "createdAt": "2026-01-01T12:00:00Z", "updatedAt": "2026-01-01T12:00:00Z" }
```

**`POST /api/v1/users/provision` (ADMIN) — création + liaison Keycloak :**

Même body que `POST /api/v1/users`. Cette route est la **seule** qui crée l'identité Keycloak
et renseigne `app_user.keycloak_id` ; `POST /api/v1/users` reste strictement local.

- `username` et `email` Keycloak = email normalisé (trim + minuscules).
- Identité Keycloak créée **activée, sans mot de passe, sans required action et sans rôle**.
  Aucun email d'invitation n'est envoyé : le mot de passe doit être défini hors bande.
- L'utilisateur local est créé `ACTIVE` ; l'identifiant interne Keycloak n'est **jamais**
  exposé (`UserResponse` reste inchangé).
- Succès : **201** + `Location: /api/v1/users/{localId}` + `UserResponse`.

| Statut | Cas |
|---|---|
| 400 | body invalide (mêmes validations que `CreateUserRequest`) |
| 401 | token absent/invalide |
| 403 | appelant non ADMIN, ou identité locale non liée / non ACTIVE |
| 404 | `agencyId` / `functionId` absent ou inactif |
| 409 | email déjà utilisé localement, ou identité Keycloak déjà existante |
| 500 | échec de persistance locale après création distante, ou intégration Keycloak mal configurée |
| 503 | Keycloak indisponible ou non configuré |

La création distante et la persistance locale ne sont pas atomiques : en cas d'échec local
après création, l'identité créée par **cette** requête est supprimée (best effort) ; une
identité préexistante n'est jamais supprimée ni liée. Un crash entre les deux étapes peut
laisser une identité Keycloak orpheline (non liée automatiquement).

### 5.2 Agencies — `/api/v1/agencies`

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| POST | `/api/v1/agencies` | ADMIN | 201 + `Location` + `AgencyResponse` | Spec/Code/Testé |
| GET | `/api/v1/agencies` | ADMIN, MANAGER | 200 + `Page<AgencyResponse>` | Spec/Code/Testé |
| GET | `/api/v1/agencies/{id}` | ADMIN, MANAGER | 200 + `AgencyResponse` | Spec/Code/Testé |
| PUT | `/api/v1/agencies/{id}` | ADMIN | 200 + `AgencyResponse` | Spec/Code/Testé |
| DELETE | `/api/v1/agencies/{id}` | ADMIN | 204 (sans corps) | Spec/Code/Testé |
| POST | `/api/v1/agencies/{id}/activate` | ADMIN | 200 + `AgencyResponse` | Spec/Code/Testé |

**Query params :** `page`, `size`, `sort`, `search` (partiel `code`/`name`), `status`.
`sort` : `id, code, name, status, createdAt, updatedAt`. Défaut `createdAt,desc`.
**Validations :** `code` `@NotBlank @Size(max=50)` (normalisé trim+UPPER, unique) ; `name`
`@NotBlank @Size(max=150)` (trim).

### 5.3 Functions — `/api/v1/functions`

Structure **identique** à Agencies : mêmes 6 endpoints, mêmes rôles, mêmes règles.
`sort` : `id, code, name, status, createdAt, updatedAt`. Codes uniques (trim+UPPER).

### 5.4 Affectations Agency / Function sur les utilisateurs

- **Pas d'endpoint dédié.** L'affectation se fait via `agencyId`/`functionId` dans
  `POST /api/v1/users` et `PUT /api/v1/users/{id}` (mode **remplacement complet** :
  omettre `agencyId`/`functionId` (ou envoyer `null`) **retire** l'affectation).
- Retour : `UserResponse.agency` / `UserResponse.function` (`null` si non affecté).
- Contrainte : la cible doit être **ACTIVE** ; sinon 404.
- Vérifié : Code + Testé (`UserServiceTest`, `UserRepositoryTest` FK).

### 5.5 Gestion des rôles Keycloak — `/api/v1/users/{id}/roles`

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| GET | `/api/v1/users/{id}/roles` | ADMIN | 200 + `UserRolesResponse` | Spec/Code/Testé |
| POST | `/api/v1/users/{id}/roles` | ADMIN | 200 + `UserRolesResponse` | Spec/Code/Testé |
| DELETE | `/api/v1/users/{id}/roles/{roleName}` | ADMIN | 200 + `UserRolesResponse` | Spec/Code/Testé |

**Body POST :** `{ "roleName": "MANAGER" }` (`@NotBlank`, max 255).
**Règles métier (vérifiées `KeycloakRoleAdminService`) :**
- Seuls les rôles **realm** de l'**allowlist** sont exposés/gérés ; les rôles client ne sont
  jamais touchés.
- `ADMIN` n'est jamais assignable/retirable.
- Un admin ne peut pas modifier **ses propres** rôles (403).
- Opérations **idempotentes** : assigner un rôle déjà présent / retirer un rôle absent → 200.
- `roles` = rôles gérés ; `hasUnmanageableRoles=true` signale la présence de rôles hors
  allowlist **sans révéler leurs noms**.
- Erreurs : 400 (nom invalide), 403 (refus métier), 404 (user local/Keycloak absent), 422
  (user local non lié), 503 (Keycloak indisponible), 500 (intégration mal configurée).
- **Dépend de la configuration serveur** : `KEYCLOAK_ADMIN_*` et
  `SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST`. Si non configuré → fail-closed.

### 5.6 Dashboard — `/api/v1/dashboard/summary`

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| GET | `/api/v1/dashboard/summary` | ADMIN, MANAGER | 200 + `DashboardSummaryResponse` | Spec/Code/Testé |

Statistiques **globales** uniquement (`users`, `agencies`, `functions` : `total`, `active`,
`inactive`) + `generatedAt`. Pas de ventilation par période, agence ou utilisateur.

### 5.7 Rapports — `/api/v1/reports`

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| GET | `/api/v1/reports?type=users\|agencies\|functions&format=pdf\|xlsx` | ADMIN | 200 + binaire | Spec/Code/Testé |

- `format` optionnel, défaut **`pdf`**.
- `Content-Disposition: attachment; filename="{type}-{yyyyMMdd}.{format}"`.
- PDF : `application/pdf` ; Excel :
  `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
- `type`/`format` inconnus → 400.
- Aucun historique, aucun stockage : le rapport est généré à la demande.

### 5.8 Notifications — `/api/v1/notifications`

Toutes les routes portent sur les notifications **de l'utilisateur authentifié**. Un accès à
la notification d'un autre utilisateur renvoie **404** (pas 403), pour ne pas divulguer son
existence.

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| GET | `/api/v1/notifications` | authentifié | 200 + `Page<NotificationResponse>` | Spec/Code/Testé |
| GET | `/api/v1/notifications/{id}` | authentifié | 200 + `NotificationResponse` | Spec/Code/Testé |
| GET | `/api/v1/notifications/unread` | authentifié | 200 + `Page<NotificationResponse>` | Spec/Code/Testé |
| GET | `/api/v1/notifications/unread/count` | authentifié | 200 + nombre | Spec/Code/Testé |
| PATCH | `/api/v1/notifications/{id}/read` | authentifié | 204 (sans corps) | Spec/Code/Testé |
| POST | `/api/v1/notifications/read/all` | authentifié | 200 + nombre marqué | Spec/Code/Testé |
| POST | `/api/v1/notifications` | ADMIN | 201 + `Location` + `NotificationResponse` | Spec/Code/Testé |

**`POST /api/v1/notifications` (émission par un ADMIN) :**
- Champs requis : `recipientUserId`, `title`, `message`, `type`.
- `link` et `metadata` optionnels.
- Le destinataire doit exister localement ; sinon 404 `"User not found"`.
- La notification est créée **non lue** et appartient au destinataire.
- Création auditée : `NOTIFICATION_CREATED`.

**Tri :** seul `createdAt` est autorisé (défaut `createdAt,desc`), y compris sur `/unread`.
**Ownership :** `GET /{id}` et `PATCH /{id}/read` renvoient 404 si la notification n'existe pas
ou appartient à un autre utilisateur. `POST /read/all` ne touche que les notifications de
l'appelant.

### 5.9 Modèles d'emails — `/api/v1/email-templates`

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| POST | `/api/v1/email-templates` | ADMIN | 201 + `Location` + `EmailTemplateResponse` | Spec/Code/Testé |
| GET | `/api/v1/email-templates` | ADMIN | 200 + `Page<EmailTemplateResponse>` | Spec/Code/Testé |
| GET | `/api/v1/email-templates/{id}` | ADMIN | 200 + `EmailTemplateResponse` | Spec/Code/Testé |
| PUT | `/api/v1/email-templates/{id}` | ADMIN | 200 + `EmailTemplateResponse` | Spec/Code/Testé |
| DELETE | `/api/v1/email-templates/{id}` | ADMIN | 204 (sans corps) | Spec/Code/Testé |
| POST | `/api/v1/email-templates/{id}/activate` | ADMIN | 200 + `EmailTemplateResponse` | Spec/Code/Testé |

- Placeholders `{{variableName}}` dans `subject`/`body`, **déclarés** dans `variables` ;
  placeholder non déclaré → 400 `"Invalid email template placeholders"`.
- `code` normalisé trim+UPPER, unique ; doublon → 409.
- Filtres : `search` (code/name), `status`, `locale`.
- `sort` : `id, code, name, locale, status, createdAt, updatedAt` ; défaut `createdAt,desc`.
- `DELETE` = désactivation logique ; `POST /{id}/activate` = réactivation.
- **Aucun envoi SMTP** : ce module ne stocke que des modèles de contenu.

### 5.10 Audit — `/api/v1/audit-logs`

| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| GET | `/api/v1/audit-logs` | ADMIN | 200 + `Page<AuditLogResponse>` | Spec/Code/Testé |

**Filtres (combinables) :** `action` (exact), `entityType` (exact), `entityId` (UUID),
`actorUserId` (UUID), `outcome` (`SUCCESS|DENIED|FAILURE|NO_CHANGE`), `from`/`to`
(instants ISO-8601 inclusifs ; `from` > `to` → 400).
**Tri :** `occurredAt, action, entityType, outcome, actorUserId` ; défaut `occurredAt,desc`,
complété par `id desc` comme tie-breaker.
**Lecture seule :** aucune route ne crée/modifie/supprime un événement.

---

## 6. Conventions transverses

- **Pagination** : `page` 0-based (défaut 0), `size` (défaut 20, plafonné à 100 — une valeur
  supérieure est ramenée à 100). Valeurs non numériques → défaut. Réponse = `Page<T>`.
- **Tri** : `sort=propriété,asc|desc`, répétable. Propriétés **whitelistées par module**
  (sinon 400). Direction invalide → 400.
- **Recherche** : `search` = correspondance partielle insensible à la casse, `%`/`_` échappés.
  Blanc = ignoré.
- **Filtre statut** : `status` exact (`ACTIVE`/`INACTIVE`) ; valeur inconnue → 400.
- **Écritures = remplacement complet** (PUT) : tous les champs mutables obligatoires.
- **Suppression = désactivation logique** (DELETE → 204), réactivation via `POST /{id}/activate`.
- **Codes de succès** : 201 (create, + `Location`), 200 (read/update/activate), 204 (delete).
- **Slash final** : non utilisé dans les chemins.

### Récapitulatif des tris par module

| Module | Propriétés triables | Défaut |
|---|---|---|
| Users | `id, email, firstName, lastName, status, createdAt, updatedAt` | `createdAt,desc` |
| Agencies / Functions | `id, code, name, status, createdAt, updatedAt` | `createdAt,desc` |
| Email templates | `id, code, name, locale, status, createdAt, updatedAt` | `createdAt,desc` |
| Notifications | `createdAt` | `createdAt,desc` |
| Audit logs | `occurredAt, action, entityType, outcome, actorUserId` | `occurredAt,desc` |

---

## 7. Incohérences / points à confirmer

1. **401 sans corps JSON** : contrairement aux autres erreurs, le 401 est un corps vide avec
   `WWW-Authenticate: Bearer`. L'intercepteur Angular doit traiter le statut, pas le JSON.
2. **`sort` non listé dans OpenAPI** : les propriétés triables sont validées côté code
   (whitelist) mais l'`enum` n'est pas exposé dans la spec → s'appuyer sur ce contrat.
3. **`GET /{id}` vs `/me`** : `/me` est une route littérale déclarée **avant** `/{id}` dans
   `UserController` ; `GET /api/v1/users/{id}` continue d'exiger ADMIN/MANAGER. Couvert par
   test (`meRouteIsNotCapturedByGetByIdRoute`).
4. **Rôles Keycloak** : dépend d'une configuration serveur (allowlist, compte de service
   Keycloak) absente par défaut en local → ces endpoints échouent en fail-closed tant que
   l'environnement n'est pas fourni.
5. **Pas de `PATCH` métier** : les mises à jour sont des remplacements complets ; seul
   `PATCH /notifications/{id}/read` existe.
6. **`agency`/`function` sur un user** : représentation **embarquée minimale**
   (id/code/name/status), pas d'objet complet avec timestamps.
7. **`keycloakId` jamais exposé** : la liaison se fait uniquement via
   `POST /api/v1/users/provision`, qui crée l'identité Keycloak et stocke son identifiant
   interne dans `app_user.keycloak_id`. Il n'existe **aucune API** pour lier un utilisateur
   local existant, modifier ou retirer un lien, ni pour auto-provisionner à la première
   connexion. L'email n'est jamais utilisé pour lier automatiquement.
8. **Provisioning non atomique** : `POST /api/v1/users/provision` n'est pas couvert par une
   transaction distribuée. Une compensation best effort supprime l'identité créée si la
   persistance locale échoue ; un crash peut laisser une identité orpheline, et un nouvel
   essai avec le même email renvoie 409.
9. **Aucun mot de passe initial** : l'identité Keycloak est activée sans credential ; le mot
   de passe est défini hors bande, sans email.
10. **Validation `status` sur list** : `status=BOGUS` → 400.
11. **Aucun envoi d'email** : les modèles sont des contenus stockés, sans livraison SMTP.
12. **Rapports sans historique** : pas de ressource de suivi, pas de fichier conservé.

---

## 8. Vérifications runtime réellement effectuées

Instance de test : build courant (`target/s-digital-platform-0.1.0-SNAPSHOT.jar`), Java 21,
port **8081**, `KEYCLOAK_ISSUER_URI=http://localhost:8180/realms/s-digital`, base PostgreSQL
Compose (`localhost:25432`). Vérifications non destructives (aucun POST/PUT/DELETE métier,
aucune écriture en base) :

| Vérification | Résultat |
|---|---|
| `GET /actuator/health` | 200 `{"status":"UP"}` |
| `GET /v3/api-docs` | 200, OpenAPI 3.1.0, opérations des 7 modules présentes |
| `GET /v3/api-docs.yaml` | 200 |
| `GET /swagger-ui/index.html` | 200 |
| `GET /api/v1/users` sans token | 401, corps vide, `WWW-Authenticate: Bearer` |
| `GET /api/v1/users/me` sans token | 401 |
| `GET /api/v1/users` avec token invalide | 401 |
| `GET /api/v1/users/me` avec token Keycloak valide mais `sub` non lié | 403 `ProblemDetail` `"Access denied"` |
| PostgreSQL 16.15 : `pg_isready`, `flyway_schema_history` | connexion OK, migrations V1–V7 `success = true` |
| Démarrage de l'application (build courant) | Flyway valide 7 migrations, schéma déjà à jour, Tomcat démarré |

**Non exécuté en runtime :** toutes les écritures (POST/PUT/PATCH/DELETE), les réponses 200
paginées réelles (nécessitent un utilisateur local lié + ACTIVE et des données), les exports
de rapports, l'émission de notifications, la gestion des rôles Keycloak (config admin requise).
Ces comportements sont vérifiés par le code et les tests automatisés.

---

## 9. Ordre conseillé d'intégration côté Frontend

1. **Auth Keycloak** : client OIDC, intercepteur HTTP ajoutant `Authorization: Bearer`,
   gestion 401 (re-login) / 403 (message).
2. **Modèle & HTTP générique** : DTOs TypeScript (§3), wrapper `Page<T>`, mapper
   `ProblemDetail` → erreurs de formulaire.
3. **Référentiels** : `Agencies` puis `Functions` (CRUD + activate/deactivate) — dépendances
   des Users.
4. **Users** : liste/recherche/filtres/pagination, CRUD, activate/deactivate, avec sélecteurs
   d'agence/fonction (listes ACTIVE).
5. **Profil courant** : `GET /api/v1/users/me` et `PUT /api/v1/users/me` (prénom/nom).
6. **Notifications** : compteur non lus, liste, détail, marquage lu / tout lu ; l'émission est
   un écran ADMIN.
7. **Dashboard** : `GET /api/v1/dashboard/summary` (cartes de synthèse).
8. **Rapports** : `GET /api/v1/reports` en téléchargement binaire (ADMIN).
9. **Journal d'audit** : table filtrable/paginée (ADMIN).
10. **Modèles d'emails** : CRUD ADMIN ; aucun envoi à prévoir.
11. **Rôles Keycloak** (module ADMIN avancé) : après validation de la config serveur.

---

## 10. Décisions en attente / limites connues

- Définition « utilisateur actif » : statut local seul (`app_user.status`) ; le statut
  `enabled` Keycloak est vérifié séparément par la gestion des rôles.
- Périmètre de visibilité MANAGER : aucune isolation par agence aujourd'hui.
- KPI Dashboard : seuls les compteurs globaux sont définis.
- Préférences de profil, historique de rapports, envoi SMTP et API de liaison Keycloak : **non
  implémentés**, à spécifier avant développement.
- Politique de rétention/purge de l'audit : non définie.
- CORS : fermé par défaut (`SDIGITAL_CORS_ALLOWED_ORIGINS` requis).

---

## 11. Note de non-modification

Cette mise à jour ne modifie que la documentation. Aucun fichier Java, test, configuration,
`pom.xml` ou migration n'a été modifié pour produire ce document. Aucune donnée PostgreSQL ni
Keycloak n'a été modifiée. Aucun commit/push. Les changements non commités de la phase
précédente sont préservés.
