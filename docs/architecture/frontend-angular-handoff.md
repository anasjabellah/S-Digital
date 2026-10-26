# Frontend Handoff — Angular Integration Summary (S-Digital API)

> **Audience :** développeur Frontend Angular.
> **Source de vérité :** `docs/architecture/frontend-api-integration-contract.md`.
> **Nature :** synthèse d'intégration. Le contrat réel a été établi par lecture **code + OpenAPI live
> + tests automatisés**. Les endpoints **non appelés en runtime** sont signalés explicitement (§7).
> Aucune validation runtime n'est revendiquée là où seul le code ou les tests ont été inspectés.

---

## 1. Endpoints exacts

Base : `/api/v1`. Identifiants = UUID. Dates = `Instant` ISO-8601 UTC.

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
| POST | `/api/v1/users` | ADMIN | 201 + `Location` + `UserResponse` |
| GET | `/api/v1/users` | ADMIN, MANAGER | 200 + `Page<UserResponse>` |
| GET | `/api/v1/users/{id}` | ADMIN, MANAGER | 200 + `UserResponse` |
| PUT | `/api/v1/users/{id}` | ADMIN | 200 + `UserResponse` |
| DELETE | `/api/v1/users/{id}` | ADMIN | 204 (sans corps) |
| POST | `/api/v1/users/{id}/activate` | ADMIN | 200 + `UserResponse` |

Affectation agence/fonction : **pas d'endpoint dédié**, via `agencyId` / `functionId` dans
`POST /api/v1/users` et `PUT /api/v1/users/{id}`. La cible doit être **ACTIVE** (sinon 404).
Retour : `UserResponse.agency` / `UserResponse.function` (nullable).

### Profil courant — `/api/v1/users/me`
| Méthode | Chemin | Rôle requis | Succès |
|---|---|---|---|
| GET | `/api/v1/users/me` | authentifié (tout rôle, aucun rôle spécifique) | 200 + `UserResponse` |

> `/me` est une route littérale déclarée avant `/{id}` : `GET /api/v1/users/{id}` continue
> d'exiger ADMIN/MANAGER, `/me` non. Nécessite toujours un utilisateur local **lié + ACTIVE**.

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
- `sort=propriété,asc|desc` répétable. Propriétés triables **whitelistées par module** (sinon 400) :
  - Users : `id, email, firstName, lastName, status, createdAt, updatedAt` (défaut `createdAt,desc`).
  - Agencies / Functions : `id, code, name, status, createdAt, updatedAt`.
- `search` : partiel, insensible à la casse (`%`/`_` échappés), blanc = ignoré.
  Champs : Users `email/firstName/lastName` ; Agencies/Functions `code/name`.
- `status` : filtre exact `ACTIVE|INACTIVE` ; valeur inconnue → 400.

---

## 3. Authentification — JWT Bearer Keycloak

- Toutes les routes `/api/v1/**` exigent `Authorization: Bearer <JWT Keycloak>`.
- JWT = realm roles Keycloak mappés en `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_USER`.
- En plus du rôle, un **utilisateur local lié et ACTIVE** est requis (garde locale) ; sinon **403**.
- Intercepteur Angular : ajouter l'en-tête ; sur **401** → (re)authentification Keycloak.
- Endpoints **publics** (sans token) : `/actuator/health`, `/actuator/info`, `/v3/api-docs`,
  `/v3/api-docs.yaml`, `/swagger-ui/index.html`.
- ⚠️ En local, si `KEYCLOAK_ISSUER_URI` n'est pas configuré, **toute** route métier renvoie 401.

---

## 4. Matrice d'accès ADMIN / MANAGER / USER

| Ressource | USER | MANAGER | ADMIN |
|---|:--:|:--:|:--:|
| `GET /api/v1/users/me` | ✔ | ✔ | ✔ |
| `GET /api/v1/users`, `GET /api/v1/users/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/users*`, `POST /{id}/activate` | ✘ | ✘ | ✔ |
| `GET /api/v1/agencies`, `/{id}` ; `GET /api/v1/functions`, `/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/agencies*`, `/functions*`, `/{id}/activate` | ✘ | ✘ | ✔ |
| `/api/v1/users/{id}/roles` (GET/POST/DELETE) | ✘ | ✘ | ✔ |

---

## 5. Gestion des erreurs

Format commun **RFC 7807 `ProblemDetail`** : `type`, `title`, `status`, `detail` (+ `errors[]` pour la validation).

| Code | Cas | Traitement Angular |
|---|---|---|
| **400** | body invalide (`@Valid`), JSON illisible, UUID malformé, `sort`/`status` invalide | Afficher les erreurs de champ (`errors[]`) ; corriger la requête |
| **401** | token absent / expiré / signature invalide | Re-login Keycloak |
| **403** | rôle insuffisant **OU** identité locale non liée / INACTIVE (`detail: "Access denied"`) | Message « accès refusé » (ne pas déduire le rôle manquant) |
| **404** | ressource absente (user / agency / function, ou cible d'affectation non ACTIVE) | Écran « introuvable » |
| **409** | email / code déjà utilisé | Message de conflit |
| **422** | utilisateur local non lié à Keycloak (routes rôles) | Message « compte non lié » |
| **500** | erreur interne / intégration Keycloak mal configurée (`detail: "An unexpected error occurred"`) | Message générique |
| **503** | Keycloak indisponible (routes rôles) | Réessayer plus tard |

---

## 6. ⚠️ Écritures et suppressions

- **PUT = remplacement complet** : tous les champs mutables obligatoires à chaque update.
  Omettre un champ (ou envoyer `null` pour `agencyId`/`functionId`) **écrase/retire** la valeur.
  Pas de `PATCH`.
- **DELETE = désactivation logique** (status → `INACTIVE`, la ligne n'est **jamais** supprimée).
  Succès = **204 sans corps**. Réactivation = `POST /{id}/activate` → 200.

---

## 7. Endpoints NON testés en runtime

Inspectés uniquement via **code + tests automatisés + OpenAPI** (aucune exécution live) :

- **Tous les POST / PUT / DELETE / activate** (pas d'appel destructif effectué).
- **Endpoints rôles Keycloak** (`/api/v1/users/{id}/roles`) — exigent token ADMIN + config Keycloak Admin.
- **Réponses 200 paginées réelles** (aucun token généré).

Exécutés en runtime : `GET /actuator/health`, `/v3/api-docs`, `/v3/api-docs.yaml`,
`/swagger-ui/index.html`, et `GET /api/v1/users` + `/api/v1/users/me` **sans token** (401 attendus).

---

## 8. Configuration serveur requise — gestion des rôles

Les endpoints `/api/v1/users/{id}/roles` dépendent d'une configuration **côté serveur** :
`KEYCLOAK_ADMIN_*` + allowlist `SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST`. Sans elle, ils échouent
(**fail-closed**). Règles métier : realm roles + allowlist uniquement, `ADMIN` jamais
assignable/retirable, auto-modification interdite, opérations **idempotentes**,
`hasUnmanageableRoles` signale des rôles hors allowlist **sans révéler leurs noms**.
Ces endpoints ne doivent pas être planifiés dans l'UI avant confirmation de la configuration serveur.

---

## 9. Ordre d'intégration conseillé

1. Auth Keycloak (client OIDC + intercepteur + gestion 401/403).
2. Modèles TypeScript + `Page<T>` + mapper `ProblemDetail`.
3. Agencies, puis Functions (CRUD + activate/deactivate).
4. Users (liste/filtres/pagination, CRUD, activate/deactivate, sélecteurs ACTIVE).
5. `GET /api/v1/users/me`.
6. Rôles Keycloak (module ADMIN) — après config serveur.
7. Dashboard — non implémenté, à planifier séparément (décisions produit requises).
