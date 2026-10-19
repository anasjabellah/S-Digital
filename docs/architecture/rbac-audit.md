# Étape 3A — Audit RBAC et stratégie d’autorisation (strict audit)

## 1. État actuel vérifié dans le code

### Sécurité (configuration)
- `SecurityConfig` : CSRF désactivé (stateless JWT), CORS explicite via `CorsConfigurationSource`, `SessionCreationPolicy.STATELESS`. Publics : OPTIONS `/**`, GET `/actuator/health/**`, OpenAPI/Swagger (`/v3/api-docs*`, `/swagger-ui*`, `/swagger-ui.html`). Authentifiés : `/api/v1/**` → `.authenticated()` (aucune règle métier par rôle). `anyRequest().denyAll()`.
- `KeycloakJwtDecoderConfig` : fail-closed si `KEYCLOAK_ISSUER_URI` vide, sinon Nimbus JWKS + validation `exp/nbf/iss`.
- `KeycloakJwtAuthenticationConverterConfig` : injecte `JwtAuthenticationConverter` avec `KeycloakRolesConverter`.
- `KeycloakRolesConverter` : realm → `ROLE_<role>` ; client configurés (`sdigital.security.keycloak.client-role-ids`, défaut `s-digital-api`) → `CLIENT_<clientId>_<role>`. Ne mappe pas scopes OAuth2. Ignore claims inconnus/malformés/nuls. Jamais null.

### Identité Keycloak ↔ local
- `KeycloakIdentityResolver` : extrait uniquement `sub` depuis `JwtAuthenticationToken` (trim + non-vide). Retourne `Optional.empty()` si auth absente/anonyme/non-JWT/sub absent/blanc. `getCurrentKeycloakSubOrThrow()` lève `IllegalStateException` générique (aucun JWT/claim divulgué).
- `UserIdentityService` (read-only) : `findLocalUserByKeycloakSub(sub)` via `userRepository.findByKeycloakId(trimmed)`. `resolveCurrentLocalUser()` combine resolver+repo. `isCurrentUserActiveLinked()` filtre `UserStatus.ACTIVE`. Pas d’auto-provisioning. Jamais basé sur email pour liaison.

### Couche domaine utilisateur
- `User` : `keycloakId` nullable + unique, `status` (ACTIVE/INACTIVE), email unique, timestamps.
- `UserRepository` : `findByKeycloakId`, `existsByKeycloakId`, `findByEmail`, `existsByEmail`, `existsByEmailAndIdNot`.
- `UserService` : CRUD existant (create/update/deactivate/getById/getAll), aucune logique Keycloak/sub.
- `UserController` : 5 endpoints `/api/v1/users*`. Aucun contrôle rôle métier, uniquement `authenticated()`.

### Tests sécurité existants
- `KeycloakJwtDecoderConfigTest` (3), `KeycloakRolesConverterTest` (22), `KeycloakRolesSecurityIntegrationTest` (10), `SecurityConfigTest` (6), `PublicEndpointsSecurityTest` (5), `UserIdentityServiceTest` (7). Total 81 tests, BUILD SUCCESS.

### Realm Keycloak importé
- Realm `s-digital`. Clients `s-digital-api` (directAccessGrants), `s-digital-api-shortlived` (5s). `testuser` sans rôles. Rôles métier absents (uniquement infra éventuels). Aucune règle métier n’utilise les autorités.

### Points clés identifiés
- Publics : OPTIONS `/**`, GET `/actuator/health/**`, `/v3/api-docs*`, `/swagger-ui*`, `/swagger-ui.html`. `/api/v1/**` nécessite auth.
- Format authorities : `ROLE_<realmRole>` (realm), `CLIENT_<clientId>_<clientRole>` (clients configurés). Scopes non mappés.
- Distinction : (1) Auth Keycloak (JWT valide) ; (2) Identité locale liée (`keycloakId==sub`) ; (3) Utilisateur local actif ((2)+`ACTIVE`).
- Tous endpoints UserController sans contrôle rôle métier.
- Risques : absence contrôle rôle/permission et propriétaire sur endpoints globaux ; état lié+INACTIVE non appliqué par règles sécurité ; non-liaison locale non traitée ; rôles business non définis.

## 2. Matrice RBAC proposée (à valider)

| Module | Action | USER | MANAGER | ADMIN | Notes (à valider) |
|---|---|---|---|---|---|
| Profil | `GET /api/v1/me` (à créer) | ✅ | ✅ | ✅ | Restreint à soi-même. |
| Profil | Mise à jour propre profil | ✅ (soi) | ✅ (soi) | ✅ (soi) | Basé identité locale (`keycloakId==sub`). |
| Utilisateurs | `GET /api/v1/users` (liste) | ❌ | ✅ | ✅ | Portée (global/filtré) — **À VALIDER**. |
| Utilisateurs | `GET /api/v1/users/{id}` | ❌ | ✅ | ✅ | Accès global vs restreint — **À VALIDER**. |
| Utilisateurs | `POST /api/v1/users` | ❌ | ❌ | ✅ | Création — **À VALIDER**. |
| Utilisateurs | `PUT /api/v1/users/{id}` | ❌ | ❌ | ✅ | **À VALIDER**. |
| Utilisateurs | `DELETE /api/v1/users/{id}` (désactivation) | ❌ | ❌ | ✅ | **À VALIDER**. |
| Dashboard | Consultation métriques/KPI | ❌ | ✅ | ✅ | Définition KPI absente — **À VALIDER**. |
| Référentiel | Lecture | ✅ | ✅ | ✅ | **À VALIDER**. |
| Référentiel | Écriture/admin | ❌ | ❌ | ✅ | **À VALIDER**. |
| Rapports | Génération/consultation/exports | ❌ | ✅ | ✅ | **À VALIDER**. |
| Audit | Consultation logs audit | ❌ | ❌ | ✅ | Sensible (RGPD) — **À VALIDER**. |
| Notifications | Consultation propres | ✅ | ✅ | ✅ | Propriétaire. |
| Notifications | Émission/envoi | ❌ | ❌ | ✅ | **À VALIDER**. |
| Emails (templates) | Consultation/modif templates | ❌ | ❌ | ✅ | **À VALIDER**. |

## 3. Questions à trancher

### 1) Quels rôles métier prévoir ?
- Options : (a) `ADMIN/MANAGER/USER` ; (b) `ADMIN/USER` ; (c) granulaire permissions.
- Conséquences : (a) équilibré ; (b) simple ; (c) complexe.
- Recommandation (proposition) : (a). **À VALIDER**.

### 2) Realm vs client roles ?
- Options : (a) realm roles → `ROLE_<R>` (hasRole) ; (b) client roles `CLIENT_<client>_<R>`.
- Conséquences : (a) idiomatique Spring ; (b) client-scoped.
- Recommandation (proposition) : (a). **À VALIDER**.

### 3) Exiger liaison+actif avant accès métier ?
- Options : (a) exiger `isCurrentUserActiveLinked()` (refuser si INACTIVE/non lié) ; (b) autoriser authentifié même non lié ; (c) différencié.
- Conséquences : (a) cohérent avec `INACTIVE` ; (b) risque d’exposition ; (c) complexe.
- Recommandation (proposition) : (a). **À VALIDER**.

### 4) Opérations réservées ADMIN ?
- Recommandation (proposition) : création/modif/désactivation utilisateurs, audit, templates emails, émission notifications. **À VALIDER**.

### 5) Accès propriétaire vs global ?
- Recommandation (proposition) : propriétaire uniquement pour `/me` + notifications propres ; autres globaux selon rôle. **À VALIDER**.

### 6) Traitement utilisateur `INACTIVE` ?
- Recommandation (proposition) : refuser accès endpoints métier (403) si INACTIVE (auth JWT reste valide). **À VALIDER**.

### 7) Comptes sans liaison locale ?
- Options : (a) pas d’auto-provisioning → accès refusé endpoints métier ; (b) JIT provisioning.
- Recommandation (proposition) : (a) conservateur. **À VALIDER** (§10 archi).

## 4. Risques et écarts
- Manque contrôle vertical (endpoints globaux sans rôle). Moyen.
- Manque contrôle horizontal (hors propriétaire). Faible-Moyen.
- État lié+INACTIVE non appliqué par règles sécurité. Moyen.
- Non-liaison locale non traitée. Moyen.
- Rôles business non définis (impossible appliquer `hasRole('ADMIN')` sans création). Moyen.
- Scopes non mappés : délibéré, faible risque.
- `keycloakId` unique : contrainte OK, collision à gérer si écriture future.

## 5. Plan d’implémentation progressif (proposition)
1. Trancher décisions §3.
2. Créer rôles business dans realm (selon §3.2).
3. Ajouter garde “lié+actif” pour `/api/v1/**` métier (403 si non conforme).
4. Ajouter `@PreAuthorize` selon matrice (§2).
5. Ajouter `/api/v1/me`.
6. Tests sécurité (401/403, INACTIVE, non lié, horizontal).
7. Vérification `mvn test` + tests manuels.

## 6. Tests exécutés
`mvn test` (Java 21) → 81 tests, 0 échecs, BUILD SUCCESS.

## 7. Livrable
Fichier créé uniquement : `docs/architecture/rbac-audit.md`

## Confirmation
Aucun fichier applicatif, test, migration ou configuration Keycloak modifié. Aucune règle de sécurité changée.