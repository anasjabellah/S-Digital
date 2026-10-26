# Frontend API Integration Contract — S-Digital Backend

> **Statut :** rapport d'audit read-only (étape 4G.3). Ce document ne modifie aucun code,
> test, configuration ou migration. Il décrit le contrat **réel** des APIs existantes,
> vérifié dans le code, la spécification OpenAPI générée et les tests. Il ne déclare aucune
> API « fonctionnelle » sur la seule présence dans Swagger.
>
> **Base de vérification :**
> - Branche `main`, HEAD `6e1c733`.
> - Application démarrée localement, port **8080**.
> - OpenAPI live : `GET /v3/api-docs` (OpenAPI 3.1.0, 12 chemins), `GET /v3/api-docs.yaml`.
> - Code source des controllers/services/DTO/exceptions/`SecurityConfig`/tests.

---

## 1. Vue d'ensemble

- **Style :** API REST versionnée sous `/api/v1`, JSON, erreurs **RFC 7807 `ProblemDetail`**.
- **Sécurité :** OAuth2 resource server, JWT **Keycloak** (`Authorization: Bearer <token>`).
- **Identifiant :** UUID (généré côté serveur), sérialisé en chaîne.
- **Dates :** `Instant` ISO-8601 UTC (ex. `2026-01-01T12:00:00Z`).
- **Pagination :** objet Spring `Page<T>` ; `page` (0-based), `size` (défaut 20, max 100), `sort=propriété,asc|desc`.
- **Rôles :** realm roles Keycloak mappés en `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_USER`.
- **Garde locale :** toute route `/api/v1/**` exige en plus un utilisateur local **lié + ACTIVE** (sinon 403).

### Convention d'en-têtes
| En-tête | Valeur |
|---|---|
| `Authorization` | `Bearer <JWT Keycloak>` (requis sauf endpoints publics) |
| `Content-Type` | `application/json` (requêtes avec body) |
| `Accept` | `application/json` |

---

## 2. Authentification & autorisation (côté Angular)

- **401 Unauthorized** : token absent, expiré, signature invalide, ou `KEYCLOAK_ISSUER_URI`
  non configuré côté serveur (cas local actuel : toutes les routes métier renvoient 401).
  → Angular doit déclencher une (re)authentification Keycloak.
- **403 Forbidden** : token valide mais soit (a) rôle insuffisant, soit (b) identité locale
  non liée ou INACTIVE. Le corps est un `ProblemDetail` générique identique dans les cas
  du garde d'identité et du contrôle de rôle :
  ```json
  { "type": "https://s-digital.io/errors/forbidden", "title": "Forbidden",
    "status": 403, "detail": "Access denied" }
  ```
  Aucun JWT, rôle, identifiant ou détail interne n'y est exposé.
  → Angular affiche un message « accès refusé » (ne pas déduire le rôle manquant).

### Matrice rôles (vérifiée dans `SecurityConfig` + tests de sécurité)
| Ressource | USER | MANAGER | ADMIN |
|---|:--:|:--:|:--:|
| `GET /api/v1/users/me` | ✔ | ✔ | ✔ |
| `GET /api/v1/users`, `GET /api/v1/users/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/users*`, `POST /{id}/activate` | ✘ | ✘ | ✔ |
| `GET /api/v1/agencies`, `/{id}` ; `GET /api/v1/functions`, `/{id}` | ✘ | ✔ | ✔ |
| `POST/PUT/DELETE /api/v1/agencies*`, `/functions*`, `/{id}/activate` | ✘ | ✘ | ✔ |
| `/api/v1/users/{id}/roles` (GET/POST/DELETE) | ✘ | ✘ | ✔ |

> Note : `/me` requiert **authentification** mais **aucun rôle** (tout utilisateur lié+ACTIVE).

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
```

### Agencies / Functions (identiques en structure)
```ts
interface AgencyResponse {
  id: string; code: string; name: string; status: 'ACTIVE'|'INACTIVE';
  createdAt: string; updatedAt: string;
}
interface CreateAgencyRequest { code: string; name: string; }
interface UpdateAgencyRequest { code: string; name: string; }
interface FunctionResponse { id: string; code: string; name: string; status: 'ACTIVE'|'INACTIVE'; createdAt: string; updatedAt: string; }
interface CreateFunctionRequest { code: string; name: string; }
interface UpdateFunctionRequest { code: string; name: string; }
```

### Keycloak realm roles
```ts
interface UserRoleDto { name: string; }         // nom de rôle realm uniquement
interface UserRolesResponse {
  roles: UserRoleDto[];                          // rôles gérés (allowlist) uniquement
  hasUnmanageableRoles: boolean;                 // true = rôles hors allowlist présents (noms jamais révélés)
}
interface AssignRoleRequest { roleName: string; }
```

### Pagination (Spring `Page<T>`)
```ts
interface Page<T> {
  content: T[];
  totalElements: number; totalPages: number; number: number; size: number;
  first: boolean; last: boolean; numberOfElements: number; empty: boolean;
  pageable?: { pageNumber: number; pageSize: number; offset: number; paged: boolean; unpaged: boolean; sort: { sorted: boolean; unsorted: boolean; empty: boolean } };
  sort?: { sorted: boolean; unsorted: boolean; empty: boolean };
}
```

---

## 4. Erreurs HTTP et traitement Angular

Format commun : `ProblemDetail` (`type`, `title`, `status`, `detail`), + `errors[]` pour la validation.

| Code | Cas | Corps / `detail` | Traitement Angular |
|---|---|---|---|
| **400** | body invalide (`@Valid`), JSON illisible, UUID malformé, `sort`/`status` invalide | `detail` = message générique ; `errors:[{field,message}]` pour la validation | Afficher les erreurs de champ ; corriger la requête |
| **401** | token absent/expiré/invalide | `ProblemDetail` | Re-login Keycloak |
| **403** | rôle insuffisant OU identité locale non liée/INACTIVE | `detail: "Access denied"` | Message « accès refusé » |
| **404** | ressource absente (user/agency/function) | `detail: "User/Agency/Function not found"` | Écran « introuvable » |
| **409** | email/code déjà utilisé | `detail: "Email already in use"` / `"Agency code already in use"` / `"Function code already in use"` | Message de conflit |
| **422** | utilisateur local non lié à Keycloak (routes rôles) | `detail: "User is not linked to a Keycloak account"` | Message « compte non lié » |
| **500** | erreur interne, intégration Keycloak mal configurée | `detail: "An unexpected error occurred"` | Message générique |
| **503** | Keycloak indisponible (routes rôles) | `detail: "Role management is temporarily unavailable"` | Réessayer plus tard |

Messages exacts vérifiés dans `GlobalExceptionHandler` et `SecurityErrorResponseWriter`.

---

## 5. Inventaire des endpoints par module

> Légende « Vérifié » :
> - **Spec** = présent et documenté dans OpenAPI live.
> - **Code** = comportement lu dans controller/service/DTO.
> - **Testé** = couvert par des tests automatisés du dépôt (unitaires/MVC/sécurité).
> - **Runtime** = réellement appelé sur l'instance locale pendant cet audit (uniquement des
>   GET non destructifs ; aucun POST/PUT/DELETE exécuté).

### 5.1 Users — `/api/v1/users`
| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| POST | `/api/v1/users` | ADMIN | 201 + `Location` + `UserResponse` | Spec/Code/Testé |
| GET | `/api/v1/users` | ADMIN, MANAGER | 200 + `Page<UserResponse>` | Spec/Code/Testé/Runtime(401) |
| GET | `/api/v1/users/{id}` | ADMIN, MANAGER | 200 + `UserResponse` | Spec/Code/Testé |
| PUT | `/api/v1/users/{id}` | ADMIN | 200 + `UserResponse` | Spec/Code/Testé |
| DELETE | `/api/v1/users/{id}` | ADMIN | 204 (sans corps) | Spec/Code/Testé |
| POST | `/api/v1/users/{id}/activate` | ADMIN | 200 + `UserResponse` | Spec/Code/Testé |
| GET | `/api/v1/users/me` | authentifié (tout rôle) | 200 + `UserResponse` | Spec/Code/Testé/Runtime(401) |

**Query params de `GET /api/v1/users` :** `page`, `size`, `sort`, `search` (partiel insensible à la casse sur `email`/`firstName`/`lastName`), `status` (`ACTIVE`|`INACTIVE`).
`sort` autorisé : `id, email, firstName, lastName, status, createdAt, updatedAt` (sinon 400). Défaut : `createdAt,desc`.

**Validations :** `email` `@NotBlank @Email @Size(max=320)` ; `firstName`/`lastName` `@NotBlank @Size(max=100)` ; `agencyId`/`functionId` optionnels mais doivent référencer une entité **ACTIVE** (sinon 404 `"Agency not found"` / `"Function not found"`).

**Exemple requête**
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

### 5.2 Agencies — `/api/v1/agencies`
| Méthode | Chemin | Rôle | Succès | Vérifié |
|---|---|---|---|---|
| POST | `/api/v1/agencies` | ADMIN | 201 + `Location` + `AgencyResponse` | Spec/Code/Testé |
| GET | `/api/v1/agencies` | ADMIN, MANAGER | 200 + `Page<AgencyResponse>` | Spec/Code/Testé |
| GET | `/api/v1/agencies/{id}` | ADMIN, MANAGER | 200 + `AgencyResponse` | Spec/Code/Testé |
| PUT | `/api/v1/agencies/{id}` | ADMIN | 200 + `AgencyResponse` | Spec/Code/Testé |
| DELETE | `/api/v1/agencies/{id}` | ADMIN | 204 (sans corps) | Spec/Code/Testé |
| POST | `/api/v1/agencies/{id}/activate` | ADMIN | 200 + `AgencyResponse` | Spec/Code/Testé |

**Query params :** `page`, `size`, `sort`, `search` (partiel `code`/`name`), `status`. `sort` : `id, code, name, status, createdAt, updatedAt`. Défaut `createdAt,desc`.
**Validations :** `code` `@NotBlank @Size(max=50)` (normalisé trim+UPPER, unique) ; `name` `@NotBlank @Size(max=150)` (trim).

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
**Règles métier (vérifiées `KeycloakRoleAdminService`)** :
- Seuls les rôles **realm** de l'**allowlist** sont exposés/gérés ; les rôles client ne sont jamais touchés.
- `ADMIN` n'est jamais assignable/retirable.
- Un admin ne peut pas modifier **ses propres** rôles (403).
- Opérations **idempotentes** : assigner un rôle déjà présent / retirer un rôle absent → 200.
- `roles` = rôles gérés ; `hasUnmanageableRoles=true` signale la présence de rôles hors allowlist **sans révéler leurs noms**.
- Erreurs possibles : 400 (nom invalide), 403 (refus métier : rôle réservé, hors allowlist, auto-modification, compte Keycloak désactivé), 404 (user local/Keycloak absent), 422 (user local non lié), 503 (Keycloak indisponible), 500 (intégration mal configurée).
- **Dépend de la configuration serveur** : `KEYCLOAK_ADMIN_*` et `SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST`. Si non configuré → fail-closed.

### 5.6 Dashboard
**Non implémenté / non documenté.** Aucun chemin `/dashboard` dans OpenAPI. (Cf. étape 4G.2.)

### 5.7 Autres
- `GET /actuator/health` → 200 `{"status":"UP"}` (public).
- `GET /actuator/info` (public selon config).
- `/v3/api-docs`, `/v3/api-docs.yaml`, `/swagger-ui/index.html` (publics).

---

## 6. Conventions transverses

- **Pagination** : `page` 0-based (défaut 0), `size` (défaut 20, plafonné à 100 — valeur
  supérieure ramenée à 100 par Spring), valeurs non numériques → défaut. Réponse = `Page<T>`.
- **Tri** : `sort=propriété,asc|desc`, répétable. Propriétés **whitelistées par module**
  (sinon 400). Direction invalide → 400.
- **Recherche** : `search` = correspondance partielle insensible à la casse, `%`/`_` échappés.
  Blanc = ignoré.
- **Filtre statut** : `status` exact (`ACTIVE`/`INACTIVE`) ; valeur inconnue → 400.
- **Écritures = remplacement complet** (PUT) : tous les champs mutables obligatoires.
- **Suppression = désactivation logique** (DELETE → 204), réactivation via `POST /{id}/activate`.
- **Codes de succès** : 201 (create, + `Location`), 200 (read/update/activate), 204 (delete).
- **Slash final** : non utilisé dans les chemins.

---

## 7. Incohérences / points à confirmer

Aucune divergence bloquante détectée entre OpenAPI, code et tests. Points de vigilance :

1. **`sort` non listé dans OpenAPI** : les propriétés triables sont validées côté code
   (whitelist) mais l'`enum` des champs triables n'est pas exposé dans la spec → le Frontend
   doit s'appuyer sur ce contrat documentaire, pas sur Swagger seul.
2. **`GET /{id}` vs `/me`** : `/me` est une route littérale déclarée **avant** `/{id}` dans
   `UserController` ; `GET /api/v1/users/{id}` continue d'exiger ADMIN/MANAGER, `/me` non.
   Couvert par test (`meRouteIsNotCapturedByGetByIdRoute`).
3. **Rôles Keycloak** : dépend d'une configuration serveur (allowlist, compte de service
   Keycloak) absente par défaut en local → ces endpoints renvoient des erreurs de config
   tant que l'environnement n'est pas fourni. **Non testés en runtime** dans cet audit.
4. **Pas de `PATCH`** : les mises à jour sont des remplacements complets → le Frontend doit
   renvoyer tous les champs à chaque update.
5. **`agency`/`function` sur un user** : représentation **embarquée minimale** (id/code/name/status),
   pas d'objet complet avec timestamps.
6. **`keycloakId` jamais exposé** : à ne pas attendre côté Frontend.
7. **Validation `status` sur list** : `status=BOGUS` → 400 (comportement documenté et testé).

---

## 8. APIs non testées en runtime

- Tous les **POST/PUT/DELETE/activate** (interdits : pas d'appel destructif).
- Les endpoints **rôles Keycloak** (exigent un token ADMIN + config Keycloak admin, non fournie).
- Les réponses **200 paginées réelles** (nécessitent un token valide, non généré).
- Seuls ont été exécutés en runtime : `GET /actuator/health`, `/v3/api-docs`, `/v3/api-docs.yaml`,
  `/swagger-ui/index.html`, et `GET /api/v1/users` + `/api/v1/users/me` **sans token** (401 attendus).

---

## 9. Ordre conseillé d'intégration côté Frontend

1. **Auth Keycloak** : client OIDC, intercepteur HTTP ajoutant `Authorization: Bearer`, gestion 401 (re-login) / 403 (message).
2. **Modèle & HTTP générique** : DTOs TypeScript (§3), wrapper `Page<T>`, mapper `ProblemDetail` → erreurs de formulaire.
3. **Référentiels** : `Agencies` puis `Functions` (CRUD + activate/deactivate) — dépendances des Users.
4. **Users** : liste/recherche/filtres/pagination, CRUD, activate/deactivate, avec sélecteurs d'agence/fonction (listes ACTIVE).
5. **Profil courant** : `GET /api/v1/users/me` (en-tête/profil).
6. **Rôles Keycloak** (module ADMIN avancé) : après validation de la config serveur et des décisions métier.
7. **Dashboard** : à planifier séparément (non implémenté — décisions produit requises, cf. 4G.2).

---

## 10. Décisions en attente (rappel)

- Définition « utilisateur actif » (statut local seul vs aussi `enabled` Keycloak).
- Périmètre de visibilité MANAGER (aucune règle d'isolation par agence aujourd'hui — décision, pas d'invention).
- KPI Dashboard (ensemble produit absent).
- Politique d'affectation agence/fonction (un seul de chaque — actuel) et gestion des utilisateurs non affectés.

---

## 11. Note de non-modification

Aucun fichier de code, test, configuration ou migration n'a été modifié. Seul ce rapport a été
créé. Aucune donnée PostgreSQL ni Keycloak touchée. Aucun commit/push. Les changements non
commités (4G.1) et les fichiers non suivis préexistants ont été préservés.
