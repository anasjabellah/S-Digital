# Frontend Handoff — Angular Integration Summary (S-Digital API)

> **Audience :** développeur Frontend Angular.
> **Source de vérité :** `docs/architecture/frontend-api-integration-contract.md`.
> **Nature :** synthèse d'intégration. Le contrat réel a été établi par lecture **code +
> OpenAPI généré + tests automatisés**. Les endpoints réellement appelés en runtime sont
> signalés au §7 ; aucun autre comportement 200 n'est revendiqué comme testé en live.

---

## 1. Endpoints exacts

Base : `/api/v1`. Identifiants = UUID. Dates = `Instant` ISO-8601 UTC.

### Dashboard — `/api/v1/dashboard`

| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| GET | `/api/v1/dashboard/summary` | ADMIN, MANAGER | 200 + `DashboardSummaryResponse` |

### Agencies — `/api/v1/agencies`

| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| POST | `/api/v1/agencies` | ADMIN | 201 + `Location` + `AgencyResponse` |
| GET | `/api/v1/agencies` | ADMIN, MANAGER | 200 + `Page<AgencyResponse>` |
| GET | `/api/v1/agencies/{id}` | ADMIN, MANAGER | 200 + `AgencyResponse` |
| PUT | `/api/v1/agencies/{id}` | ADMIN | 200 + `AgencyResponse` |
| DELETE | `/api/v1/agencies/{id}` | ADMIN | 204 (sans corps) |
| POST | `/api/v1/agencies/{id}/activate` | ADMIN | 200 + `AgencyResponse` |

### Functions — `/api/v1/functions`

Structure **identique** à Agencies (6 endpoints, mêmes rôles, mêmes règles) :
`POST /`, `GET /`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/activate`.

### Users — `/api/v1/users`

| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| POST | `/api/v1/users` | ADMIN | 201 + `Location` + `UserResponse` (local seul, pas d'identité Keycloak) |
| POST | `/api/v1/users/provision` | ADMIN | 201 + `Location` + `UserResponse` (crée et lie l'identité Keycloak) |
| GET | `/api/v1/users` | ADMIN, MANAGER | 200 + `Page<UserResponse>` |
| GET | `/api/v1/users/{id}` | ADMIN, MANAGER | 200 + `UserResponse` |
| PUT | `/api/v1/users/{id}` | ADMIN | 200 + `UserResponse` |
| DELETE | `/api/v1/users/{id}` | ADMIN | 204 (sans corps) |
| POST | `/api/v1/users/{id}/activate` | ADMIN | 200 + `UserResponse` |

Affectation agence/fonction : **pas d'endpoint dédié**, via `agencyId` / `functionId` dans
`POST /api/v1/users`, `POST /api/v1/users/provision` et `PUT /api/v1/users/{id}`. La cible doit
être **ACTIVE** (sinon 404). Retour : `UserResponse.agency` / `UserResponse.function` (nullable).

**Provisioning — `POST /api/v1/users/provision` (ADMIN) :** même body que `POST /api/v1/users`.
Crée l'utilisateur local **et** son identité Keycloak (username/email = email normalisé), puis
les lie via l'identifiant interne Keycloak. L'identité est **activée sans mot de passe, sans
required action et sans rôle** ; aucun email n'est envoyé, le mot de passe doit être défini
hors bande. Succès : **201** + `Location` + `UserResponse` (`keycloakId` jamais exposé).
Erreurs : 400 validation, 401 token, 403 non-ADMIN ou identité locale non liée/INACTIVE,
404 agence/fonction, 409 email local déjà utilisé ou identité Keycloak existante,
500 échec de persistance locale / intégration mal configurée, 503 Keycloak indisponible ou non
configuré. En cas d'échec local après création distante, l'identité créée par cette requête est
supprimée (best effort) ; aucune identité préexistante n'est supprimée ni liée.

### Profil courant — `/api/v1/users/me`

| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| GET | `/api/v1/users/me` | authentifié (tout rôle) | 200 + `UserResponse` |
| PUT | `/api/v1/users/me` | authentifié (tout rôle) | 200 + `UserResponse` |

- `PUT` n'accepte que `firstName` et `lastName` (les deux requis, max 100).
- `email`, `status`, `agency`, `function` et le lien Keycloak ne sont **jamais** modifiés ici.
- `/me` est une route littérale déclarée avant `/{id}` : `GET /api/v1/users/{id}` continue
  d'exiger ADMIN/MANAGER.
- Nécessite toujours un utilisateur local **lié + ACTIVE**.

### Notifications — `/api/v1/notifications`

Toutes les routes de lecture/marquage portent sur les notifications **de l'appelant**. Accéder
à la notification d'un autre utilisateur renvoie **404** (jamais 403).

| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| GET | `/api/v1/notifications` | authentifié | 200 + `Page<NotificationResponse>` |
| GET | `/api/v1/notifications/{id}` | authentifié | 200 + `NotificationResponse` |
| GET | `/api/v1/notifications/unread` | authentifié | 200 + `Page<NotificationResponse>` |
| GET | `/api/v1/notifications/unread/count` | authentifié | 200 + nombre |
| PATCH | `/api/v1/notifications/{id}/read` | authentifié | 204 (sans corps) |
| POST | `/api/v1/notifications/read/all` | authentifié | 200 + nombre marqué |
| POST | `/api/v1/notifications` | **ADMIN** | 201 + `Location` + `NotificationResponse` |

**Body d'émission (ADMIN) :**
```ts
{ recipientUserId: string; title: string; message: string; type: string;
  link?: string | null; metadata?: Record<string, unknown> | null; }
```
Le destinataire doit exister localement (sinon 404). La notification est créée non lue et
appartient au destinataire. Tri disponible : `createdAt` uniquement.

### Rapports — `/api/v1/reports`

| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| GET | `/api/v1/reports?type=users\|agencies\|functions&format=pdf\|xlsx` | ADMIN | 200 + fichier |

- `format` optionnel, défaut `pdf`.
- Réponse binaire ; récupérer le nom via `Content-Disposition`
  (`{type}-{yyyyMMdd}.{format}`).
- En Angular : `responseType: 'blob'` et déclencher le téléchargement.
- Aucun historique : chaque appel régénère le fichier.

### Journal d'audit — `/api/v1/audit-logs`

| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| GET | `/api/v1/audit-logs` | ADMIN | 200 + `Page<AuditLogResponse>` |

Filtres : `action`, `entityType`, `entityId`, `actorUserId`, `outcome`
(`SUCCESS|DENIED|FAILURE|NO_CHANGE`), `from`, `to` (ISO-8601 inclusifs).
Tri : `occurredAt, action, entityType, outcome, actorUserId` ; défaut `occurredAt,desc`.
Lecture seule.

### Modèles d'emails — `/api/v1/email-templates` (ADMIN)

| Méthode | Chemin | Succès |
|---|---|---|
| POST | `/api/v1/email-templates` | 201 + `Location` + `EmailTemplateResponse` |
| GET | `/api/v1/email-templates` | 200 + `Page<EmailTemplateResponse>` |
| GET | `/api/v1/email-templates/{id}` | 200 + `EmailTemplateResponse` |
| PUT | `/api/v1/email-templates/{id}` | 200 + `EmailTemplateResponse` |
| DELETE | `/api/v1/email-templates/{id}` | 204 (sans corps) |
| POST | `/api/v1/email-templates/{id}/activate` | 200 + `EmailTemplateResponse` |

- `code` trim+UPPER, unique (doublon → 409).
- Placeholders `{{variable}}` déclarés dans `variables` (sinon 400).
- Filtres : `search`, `status`, `locale`.
- **Aucun envoi SMTP** : contenu stocké uniquement.

### Gestion des rôles Keycloak — `/api/v1/users/{id}/roles` (ADMIN)

| Méthode | Chemin | Succès |
|---|---|---|
| GET | `/api/v1/users/{id}/roles` | 200 + `UserRolesResponse` |
| POST | `/api/v1/users/{id}/roles` | 200 + `UserRolesResponse` |
| DELETE | `/api/v1/users/{id}/roles/{roleName}` | 200 + `UserRolesResponse` |

Body POST : `{ "roleName": "MANAGER" }`. Voir §8 (configuration serveur requise).

---

## 2. DTOs TypeScript

```ts
type UserStatus = 'ACTIVE' | 'INACTIVE';
type EmailTemplateStatus = 'ACTIVE' | 'INACTIVE';
type AuditOutcome = 'SUCCESS' | 'DENIED' | 'FAILURE' | 'NO_CHANGE';

interface UserAgencyDto   { id: string; code: string; name: string; status: UserStatus; }
interface UserFunctionDto { id: string; code: string; name: string; status: UserStatus; }

interface UserResponse {
  id: string; email: string; firstName: string; lastName: string;
  status: UserStatus;
  agency: UserAgencyDto | null;
  function: UserFunctionDto | null;
  createdAt: string; updatedAt: string;
} // keycloakId volontairement ABSENT

interface CreateUserRequest {
  email: string; firstName: string; lastName: string;
  agencyId?: string | null; functionId?: string | null;
}
interface UpdateUserRequest {
  email: string; firstName: string; lastName: string;
  agencyId?: string | null; functionId?: string | null;
}
interface UpdateCurrentUserRequest { firstName: string; lastName: string; }

interface AgencyResponse {
  id: string; code: string; name: string; status: UserStatus;
  createdAt: string; updatedAt: string;
}
interface CreateAgencyRequest { code: string; name: string; }
interface UpdateAgencyRequest { code: string; name: string; }

interface FunctionResponse {
  id: string; code: string; name: string; status: UserStatus;
  createdAt: string; updatedAt: string;
}
interface CreateFunctionRequest { code: string; name: string; }
interface UpdateFunctionRequest { code: string; name: string; }

interface NotificationResponse {
  id: string; userId: string; title: string; message: string; type: string;
  read: boolean; createdAt: string; readAt: string | null;
  link: string | null; metadata: Record<string, unknown> | null;
}
interface CreateNotificationRequest {
  recipientUserId: string; title: string; message: string; type: string;
  link?: string | null; metadata?: Record<string, unknown> | null;
}

interface EmailTemplateResponse {
  id: string; code: string; name: string; subject: string; body: string;
  locale: string; variables: string[]; status: EmailTemplateStatus;
  createdAt: string; updatedAt: string;
}
interface CreateEmailTemplateRequest {
  code: string; name: string; subject: string; body: string;
  locale: string; variables: string[];
}
interface UpdateEmailTemplateRequest extends CreateEmailTemplateRequest {}

interface EntityStatusStatistics { total: number; active: number; inactive: number; }
interface DashboardSummaryResponse {
  users: EntityStatusStatistics;
  agencies: EntityStatusStatistics;
  functions: EntityStatusStatistics;
  generatedAt: string;
}

interface AuditLogResponse {
  id: string; occurredAt: string; actorUserId: string | null;
  action: string; entityType: string; entityId: string | null;
  outcome: AuditOutcome; details: Record<string, unknown> | null;
}

interface UserRoleDto { name: string; }
interface UserRolesResponse {
  roles: UserRoleDto[];
  hasUnmanageableRoles: boolean; // true = rôles hors allowlist présents (noms jamais révélés)
}
interface AssignRoleRequest { roleName: string; }
```

### Contrat de pagination (Spring `Page<T>`)

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

- `page` : **0-based** (défaut 0). `size` : défaut **20**, plafonné à **100**.
- `sort=propriété,asc|desc` répétable. Propriétés triables **whitelistées par module**
  (sinon 400) :
  - Users : `id, email, firstName, lastName, status, createdAt, updatedAt`
  - Agencies / Functions : `id, code, name, status, createdAt, updatedAt`
  - Email templates : `id, code, name, locale, status, createdAt, updatedAt`
  - Notifications : `createdAt` uniquement
  - Audit : `occurredAt, action, entityType, outcome, actorUserId`
- `search` : partiel, insensible à la casse (`%`/`_` échappés), blanc = ignoré.
- `status` : filtre exact `ACTIVE|INACTIVE` (users, agencies, functions, email templates) ;
  valeur inconnue → 400.

---

## 3. Authentification — JWT Bearer Keycloak

- Toutes les routes `/api/v1/**` exigent `Authorization: Bearer <JWT Keycloak>`.
- JWT = realm roles Keycloak mappés en `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_USER`.
- En plus du rôle, un **utilisateur local lié et ACTIVE** est requis
  (`app_user.keycloak_id` = claim `sub`) ; sinon **403**.
- Intercepteur Angular : ajouter l'en-tête ; sur **401** → (re)authentification Keycloak.

### ⚠️ Forme des réponses 401 / 403

- **401** : **corps vide**, en-tête `WWW-Authenticate: Bearer`. Ne pas tenter de parser un
  JSON. Déclencher le re-login sur le statut.
- **403** : `ProblemDetail` JSON :
  ```json
  { "type": "https://s-digital.io/errors/forbidden", "title": "Forbidden",
    "status": 403, "detail": "Access denied" }
  ```

- Endpoints **publics** (sans token) : `/actuator/health`, `/v3/api-docs`,
  `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/**`.
  `/actuator/info` **n'est pas public**.
- ⚠️ En local, si `KEYCLOAK_ISSUER_URI` n'est pas configuré, **toute** route métier renvoie 401.
- ⚠️ Sans ligne locale liée, **toute** route métier renvoie 403.

---

## 4. Matrice d'accès ADMIN / MANAGER / USER

| Ressource | USER | MANAGER | ADMIN |
|---|:--:|:--:|:--:|
| `GET/PUT /api/v1/users/me` | ✔ | ✔ | ✔ |
| `GET /api/v1/users`, `GET /api/v1/users/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/users*`, `POST /{id}/activate` | ✘ | ✘ | ✔ |
| `/api/v1/users/{id}/roles` (GET/POST/DELETE) | ✘ | ✘ | ✔ |
| `GET /api/v1/agencies`, `/{id}` ; `GET /api/v1/functions`, `/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/agencies*`, `/functions*`, `/{id}/activate` | ✘ | ✘ | ✔ |
| `GET /api/v1/dashboard/summary` | ✘ | ✔ | ✔ |
| Notifications de l'utilisateur courant (lecture/marquage) | ✔ | ✔ | ✔ |
| `POST /api/v1/notifications` | ✘ | ✘ | ✔ |
| `GET /api/v1/reports` | ✘ | ✘ | ✔ |
| `GET /api/v1/audit-logs` | ✘ | ✘ | ✔ |
| `/api/v1/email-templates*` | ✘ | ✘ | ✔ |

---

## 5. Gestion des erreurs

Format commun **RFC 7807 `ProblemDetail`** : `type`, `title`, `status`, `detail`
(+ `errors[]` pour la validation). **Exception : 401 = corps vide.**

| Code | Cas | Traitement Angular |
|---|---|---|
| **400** | body invalide (`@Valid`), JSON illisible, UUID malformé, `sort`/enum invalide | Afficher les erreurs de champ (`errors[]`) ; corriger la requête |
| **401** | token absent / expiré / signature invalide | Re-login Keycloak (statut seul, corps vide) |
| **403** | rôle insuffisant **OU** identité locale non liée / INACTIVE (`detail: "Access denied"`) | Message « accès refusé » |
| **404** | ressource absente, cible d'affectation non ACTIVE, notification d'un autre utilisateur | Écran « introuvable » |
| **409** | email / code déjà utilisé | Message de conflit |
| **422** | utilisateur local non lié à Keycloak (routes rôles) | Message « compte non lié » |
| **500** | erreur interne / intégration Keycloak mal configurée | Message générique |
| **503** | Keycloak indisponible (routes rôles) | Réessayer plus tard |

---

## 6. ⚠️ Écritures, suppressions et téléchargements

- **PUT = remplacement complet** : tous les champs mutables obligatoires à chaque update.
  Omettre un champ (ou envoyer `null` pour `agencyId`/`functionId`) **écrase/retire** la valeur.
- **Exception** : `PUT /api/v1/users/me` ne remplace que `firstName`/`lastName` ; les autres
  champs du profil sont hors de portée de cette route.
- **PATCH** : un seul endpoint, `PATCH /api/v1/notifications/{id}/read`, sans corps.
- **DELETE = désactivation logique** (status → `INACTIVE`, la ligne n'est **jamais** supprimée).
  Succès = **204 sans corps**. Réactivation = `POST /{id}/activate` → 200.
- **Rapports** : utiliser `responseType: 'blob'` ; lire `Content-Disposition` pour le nom de
  fichier ; gérer les erreurs 400/401/403 avant de traiter le blob.
- **Notifications** : `POST /read/all` renvoie le nombre marqué ; le compteur non lus s'obtient
  via `GET /unread/count`.
- **CORS** : le backend est fermé par défaut ; l'origine Angular doit être déclarée dans
  `SDIGITAL_CORS_ALLOWED_ORIGINS`.

---

## 7. Endpoints NON testés en runtime

Inspectés uniquement via **code + tests automatisés + OpenAPI** (aucune exécution live) :

- **Tous les POST / PUT / PATCH / DELETE / activate** (pas d'appel destructif effectué), y
  compris `POST /api/v1/users/provision` (aucune identité Keycloak réelle créée).
- **Réponses 200 métier réelles** (aucun utilisateur local lié + ACTIVE n'a été créé).
- **Exports de rapports** (binaire non généré en live).
- **Émission de notifications** et **gestion des rôles Keycloak**.

Exécutés en runtime : `GET /actuator/health`, `/v3/api-docs`, `/v3/api-docs.yaml`,
`/swagger-ui/index.html`, `GET /api/v1/users` **sans token** (401), et
`GET /api/v1/users/me` avec un **token Keycloak valide mais non lié** (403 attendu).

---

## 8. Configuration serveur requise — gestion des rôles

Les endpoints `/api/v1/users/{id}/roles` dépendent d'une configuration **côté serveur** :
`KEYCLOAK_ADMIN_*` + allowlist `SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST`. Sans elle, ils échouent
(**fail-closed**). Règles métier : realm roles + allowlist uniquement, `ADMIN` jamais
assignable/retirable, auto-modification interdite, opérations **idempotentes**,
`hasUnmanageableRoles` signale des rôles hors allowlist **sans révéler leurs noms**.
Ces endpoints ne doivent pas être planifiés dans l'UI avant confirmation de la configuration
serveur.

---

## 9. Ordre d'intégration conseillé

1. Auth Keycloak (client OIDC + intercepteur + gestion 401/403).
2. Modèles TypeScript + `Page<T>` + mapper `ProblemDetail`.
3. Agencies, puis Functions (CRUD + activate/deactivate).
4. Users (liste/filtres/pagination, CRUD, activate/deactivate, sélecteurs ACTIVE).
5. Profil courant : `GET` + `PUT /api/v1/users/me`.
6. Notifications : compteur, liste, détail, marquage lu ; écran d'émission ADMIN.
7. Dashboard : `GET /api/v1/dashboard/summary`.
8. Rapports : téléchargement binaire (`type`, `format`).
9. Journal d'audit : table filtrable (ADMIN).
10. Modèles d'emails : CRUD ADMIN (aucun envoi).
11. Rôles Keycloak (module ADMIN) — après config serveur.

---

## 10. Limites connues

- Aucun envoi SMTP (modèles de contenu uniquement).
- Aucune API de liaison d'un utilisateur local **existant** (ni modification/suppression du
  lien) et aucune auto-provisioning à la première connexion. La seule liaison possible est
  `POST /api/v1/users/provision`, qui crée l'identité Keycloak en même temps que la ligne
  locale ; un utilisateur local créé via `POST /api/v1/users` reste non lié
  (`keycloak_id = NULL`) et reçoit 403 jusqu'à un traitement hors bande.
- Provisioning non atomique : compensation best effort, pas d'outbox/réconciliation ; un crash
  peut laisser une identité Keycloak orpheline (jamais liée automatiquement, 409 au retry).
- Aucun mot de passe initial et aucun email d'invitation : l'identité Keycloak est activée
  sans credential, définie hors bande.
- Un changement d'email local ne met pas à jour le username/email Keycloak.
- Le premier ADMIN doit être amorcé hors bande (rôle Keycloak + `keycloak_id` manuel).
- Aucune préférence de profil, aucun historique de rapports, aucun `PATCH` métier.
- Dashboard : compteurs globaux seulement.
- Audit : consultation seule, pas de purge/rétention.
- 401 sans corps JSON.
- CORS fermé par défaut.
