# Architecture Backend — S-Digital Platform

> **Statut :** proposition d'architecture (aucun développement démarré).
> **Portée :** document de conception uniquement. Aucun fichier Java, `pom.xml`, `application.yml`, migration Flyway ou schéma PostgreSQL n'a été modifié pour produire ce document.
> **Version :** 0.1.0-SNAPSHOT (projet) — document v1.
> **Dernière mise à jour :** 2026-10-16.

---

## 1. Résumé de l'audit

### 1.1 Inventaire de l'existant

| Élément | Détail |
|---|---|
| Build | Maven, `spring-boot-starter-parent` 3.4.1, `java.version=21` |
| Modules métier | **1 seul** : `user` |
| Fichiers Java (main) | 14 |
| Contrôleurs REST | **1** : `UserController` (`/api/v1/users`) |
| Endpoints exposés | **5** (POST, GET list, GET by id, PUT, DELETE logique) |
| Tests | 5 classes de test / **28 tests**, tous verts |
| Migrations Flyway | `V1__create_user.sql` (table `app_user`) |
| Base | PostgreSQL 16 (conteneur `sdigital-postgres`, port hôte `25432`) |
| OpenAPI | springdoc `2.8.0`, Swagger UI + `/v3/api-docs` |
| Keycloak | **non intégré** |
| Docs / specs / tickets dans le dépôt | **aucun** (pas de `docs/`, ni `.md`, ni export Jira) |

**Structure actuelle des packages**

```
com.sdigital.platform
├── SDigitalApplication
├── common
│   ├── config      → OpenApiConfig
│   └── exception   → GlobalExceptionHandler (+ ValidationError)
└── user
    ├── controller  → UserController
    ├── service     → UserService
    ├── repository  → UserRepository
    ├── entity      → User, UserStatus
    ├── dto         → CreateUserRequest, UpdateUserRequest, UserResponse
    ├── mapper      → UserMapper
    └── exception   → UserNotFoundException, EmailAlreadyUsedException
```

### 1.2 Fonctionnalités implémentées

- Création d'utilisateur (`POST`), lecture unitaire (`GET /{id}`), liste paginée (`GET`), mise à jour complète (`PUT`), **désactivation logique** (`DELETE` → `status=INACTIVE`).
- Normalisation email (trim + minuscules) et **unicité de l'email**.
- Pagination / tri via Spring Data (`page`, `size` plafonné à 100, `sort`).
- Gestion centralisée des erreurs en `ProblemDetail` (RFC 7807) : 400 / 404 / 409 / 500.
- Documentation OpenAPI complète + Swagger UI (`/api/v1`).
- Actuator : `health`, `info` exposés.
- Tests unitaires, de tranche MVC et d'intégration (Testcontainers PostgreSQL 16).

### 1.3 Fonctionnalités partiellement implémentées

| Élément | État | Limite |
|---|---|---|
| `keycloakId` | Colonne `keycloak_id` (unique) + champ entité présents | Jamais renseigné, jamais exposé ; **aucun** lien Keycloak |
| Statut utilisateur | `ACTIVE` / `INACTIVE` | Désactivation seulement, **pas de réactivation** |
| Tri paginé | `sort` accepté | Propriété inconnue → **500** (non gérée finement) |
| Observabilité | `health`, `info` | Pas de métriques, tracing, logs structurés |
| Rôles | — | Notion inexistante |

### 1.4 Fonctionnalités absentes

Authentification / autorisation ; rôles & permissions ; profil utilisateur (self-service) ; données de référence ; dashboard & statistiques ; rapports / exports PDF-Excel ; audit & historique ; notifications ; modèles & envoi d'emails ; `PATCH` (mise à jour partielle) ; recherche / filtrage ; suppression physique ; i18n ; cache ; rate limiting ; multi-tenant.

### 1.5 Contraintes techniques à préserver

- **Flyway = source de vérité** du schéma ; Hibernate en `ddl-auto=validate` ; jamais de `ddl-auto=update`.
- `spring.jpa.open-in-view: false`.
- `spring.data.web.pageable.max-page-size: 100` (garde-fou).
- **Versionnement d'API** sous `/api/v1` (pas de rupture des routes existantes).
- Identifiants **UUID** générés par l'application (`GenerationType.UUID`).
- Erreurs au format **`ProblemDetail`**.
- Code et commentaires techniques **en anglais** ; pas de **Lombok** ni **MapStruct** (mapper manuel).
- **28 tests** existants doivent rester verts.
- springdoc **2.8.0** figé (compatible Spring Boot 3.4.1).

### 1.6 Dépendances et risques d'intégration

| Risque | Impact | Mitigation proposée |
|---|---|---|
| Intégration Keycloak (nouvelle dépendance, realm, validation JWT, mapping des rôles) | Élevé — transverse à tous les endpoints | Étape dédiée, contrat de rôles figé avant codage (cf. §7 et §10) |
| Environnement dev instable (relais WSL2/Docker, ports `5432/5433` occupés) | Moyen — démarrage local | Port conteneur `25432` pérennisé ; documenter le contournement |
| Évolution du schéma sans casser l'existant | Moyen | Migrations additives V2+, jamais destructives (cf. §8) |
| Exports volumineux (PDF/Excel) | Moyen | Limites de taille, génération en streaming, async si nécessaire |
| Données personnelles dans l'audit/notifications | Élevé (RGPD) | Minimisation, retention, décision produit (cf. §10) |
| Absence de rôles → toutes les API ouvertes | Élevé | Traiter avant mise en production |

---

## 2. Architecture actuelle et architecture cible

### 2.1 Architecture actuelle

Monolithe Spring Boot en **couches par module** (package-par-feature) :

```
HTTP → UserController → UserService → UserRepository → PostgreSQL
                         (mapper)         (Flyway)
        GlobalExceptionHandler (transverse)
        OpenApiConfig (transverse)
```

Points forts déjà en place : séparation controller/service/repository/entity/dto/mapper, erreurs centralisées, OpenAPI, tests à plusieurs niveaux.

### 2.2 Architecture cible

**Choix : monolithe modulaire** (package-par-domaine) au sein de l'application Spring Boot actuelle.

Justification : aucune contrainte ne justifie des microservices à ce stade (équipe, volumétrie, cohérence transactionnelle). Le découpage par domaine offre les mêmes frontières logiques, extensibles plus tard en services si un besoin réel apparaît.

Principes :
1. **Un package par domaine** sous `com.sdigital.platform.<domain>`, chacun exposant des API internes explicites (services) et masquant ses détails (entités/repositories).
2. **Couches internes homogènes** : `controller / service / repository / entity / dto / mapper / exception`.
3. **`common`** regroupe le transverse : configuration, erreurs, sécurité, audit technique, OpenAPI, pagination.
4. **Communication inter-modules via services** (appels Java), jamais via les repositories d'un autre domaine.
5. **Dépendances descendantes** : `controller → service → repository`. Les modules peuvent dépendre de `common` ; les dépendances inter-domaines sont explicitement listées (§4).

```
┌──────────────────────────────────────────────────────────────┐
│                        common (transverse)                     │
│  config · exception/error · security(Keycloak) · audit · web  │
└──────────────────────────────────────────────────────────────┘
        ▲                ▲               ▲              ▲
        │                │               │              │
   ┌─────────┐      ┌─────────┐    ┌───────────┐  ┌───────────┐
   │  user   │      │  role   │    │ reference │  │  profile  │
   └─────────┘      └─────────┘    └───────────┘  └───────────┘
        │                │               │              │
        ▼                ▼               ▼              ▼
   ┌─────────┐      ┌──────────┐   ┌───────────┐  ┌────────────┐
   │ audit   │◄─────│notificat.│   │ dashboard │  │ reporting  │
   └─────────┘      └──────────┘   └───────────┘  └────────────┘
                          │
                          ▼
                   ┌────────────┐
                   │   email    │
                   └────────────┘
```

---

## 3. Organisation proposée des packages

```
com.sdigital.platform
├── SDigitalApplication
├── common
│   ├── config                 # OpenApiConfig, (JacksonConfig, PageableConfig, Clock…)
│   ├── error                  # ProblemDetail helpers, types d'erreur, exceptions de base
│   ├── exception              # GlobalExceptionHandler (+ ValidationError)
│   ├── web                    # DTOs transverses (PaginatedResponse<T>), filtres
│   ├── security               # Keycloak: SecurityConfig, JWT converter, autorités
│   └── audit                  # Infrastructure d'audit (intercepteurs/AOP, contexte)
├── user                       # [EXISTANT] gestion des utilisateurs
├── role                       # rôles, permissions, affectations
├── profile                    # profil self-service ("moi")
├── reference                  # données de référence (nomenclatures)
├── dashboard                  # indicateurs & statistiques
├── reporting                  # rapports et exports PDF/Excel
├── notification               # notifications (in-app et autres canaux)
├── email                      # modèles d'emails & envoi
└── audit                      # journal d'audit (lecture/consultation)
```

> Le domaine `audit` (consultation) est séparé de `common/audit` (capture technique transverse). Les deux seront confirmés en §10.

---

## 4. Description des modules

> Légende « Décisions à confirmer » : questions ouvertes reprises en §10.

### 4.1 `common` — socle transverse
- **Responsabilité :** configuration, gestion centralisée des erreurs, sécurité Keycloak, pagination commune, observabilité, documentation OpenAPI.
- **Composants :** `GlobalExceptionHandler` (existant), `OpenApiConfig` (existant), futur `SecurityConfig` + `JwtAuthConverter`, base d'exceptions domaine, `PaginatedResponse<T>` (optionnel).
- **Dépendances :** aucune métier.
- **Entités/DTO :** aucun (sauf DTO transverse de pagination si retenu).
- **Décisions :** standard de pagination (garder `Page` Spring vs DTO maison) ; politique de gestion d'erreurs pour `sort` invalide (cf. §10).

### 4.2 `user` — gestion des utilisateurs *(existant, à étendre)*
- **Responsabilité :** cycle de vie des comptes (création, lecture, mise à jour, activation/désactivation), lien avec l'identité Keycloak.
- **Composants :** inchangés + extension service/controller (réactivation, filtrage, liaison `keycloakId`).
- **Dépendances :** `role` (affectation de rôles), `common/audit` (traçage), `common/security` (autorisations).
- **Entités/DTO :** `User` (existant), futurs DTO de filtrage, `ReactivateUserRequest` éventuel, DTO d'affectation de rôles.
- **Décisions :** réactivation (endpoint dédié vs `PATCH`) ; suppression physique ; granularité du filtrage.

### 4.3 `role` — rôles & permissions
- **Responsabilité :** définir rôles et permissions, affecter rôles↔utilisateurs et permissions↔rôles.
- **Composants :** `RoleController`, `RoleService`, `RoleRepository`, `PermissionService`, mapping.
- **Dépendances :** `user` (affectation), `common/security`.
- **Entités/DTO :** `Role`, `Permission`, jointures `user_role`, `role_permission` ; DTO `RoleResponse`, `RoleRequest`, `AssignRolesRequest`.
- **Décisions :** **source de vérité des rôles** (Keycloak vs base locale) ; RBAC simple vs permissions fines ; rôles figés vs dynamiques.

### 4.4 `profile` — profil utilisateur (self-service)
- **Responsabilité :** consulter/modifier **son** profil (l'utilisateur connecté), préférences.
- **Composants :** `ProfileController` (`/me`), `ProfileService`.
- **Dépendances :** `user`, `common/security` (identité courante), `email`/`notification` (éventuel).
- **Entités/DTO :** `UserPreference` (préférences : langue, fuseau, notifications) ; DTO `ProfileResponse`, `UpdateProfileRequest`.
- **Décisions :** champs du profil (au-delà de prénom/nom), gestion du mot de passe (déléguée à Keycloak ?).

### 4.5 `reference` — données de référence
- **Responsabilité :** nomenclatures stables (pays, devises, statuts, catégories…), lecture publique, écriture administrateur.
- **Composants :** `ReferenceController`, `ReferenceService`, `ReferenceRepository`.
- **Dépendances :** `common/security` (écriture restreinte).
- **Entités/DTO :** *au choix* `ReferenceType` + `ReferenceValue`, ou tables dédiées par nomenclature. DTO `ReferenceValueResponse`, `ReferenceValueRequest`.
- **Décisions :** **table générique vs tables dédiées** ; qui administre ; cache.

### 4.6 `dashboard` — dashboard & statistiques
- **Responsabilité :** indicateurs agrégés (nombre d'utilisateurs, actifs, rôles, activité).
- **Composants :** `DashboardController`, `DashboardService` (requêtes d'agrégation), éventuel `StatisticsRepository`.
- **Dépendances :** `user`, `role`, `audit` (activité).
- **Entités/DTO :** pas d'entité propre a priori (agrégations) ; DTO `DashboardSummaryResponse`, `StatisticsPoint`.
- **Décisions :** **définition des métriques** (règles produit absentes) ; temps réel vs précalcul ; fenêtre temporelle.

### 4.7 `reporting` — rapports & exports
- **Responsabilité :** générer des rapports et exports **PDF / Excel** (et CSV ?).
- **Composants :** `ReportController`, `ReportService`, `ReportGenerator` (PDF/Excel).
- **Dépendances :** `user`, `reference`, `audit`.
- **Entités/DTO :** `ReportRequest`, `ReportMetadata` (éventuel) ; pas d'entité obligatoire.
- **Décisions :** **bibliothèque** (iText/OpenPDF vs Apache POI) ; synchrone vs asynchrone ; stockage des fichiers générés ; limites volumétrie.

### 4.8 `audit` — audit & historique
- **Responsabilité :** enregistrer et consulter l'historique des actions sensibles.
- **Composants :** `AuditLogController` (lecture), `AuditService`, `AuditLogRepository` ; capture via `common/audit` (AOP/intercepteur).
- **Dépendances :** `user` (acteur), `common/security`.
- **Entités/DTO :** `AuditLog` ; DTO `AuditLogResponse` + filtres.
- **Décisions :** **périmètre** des actions tracées ; rétention ; stockage des données personnelles (RGPD) ; immutabilité.

### 4.9 `notification` — notifications
- **Responsabilité :** créer, distribuer et consulter des notifications pour les utilisateurs.
- **Composants :** `NotificationController`, `NotificationService`, `NotificationRepository`, éventuel `NotificationDispatcher`.
- **Dépendances :** `user`, `profile` (préférences), `email` (canal email).
- **Entités/DTO :** `Notification`, `NotificationRecipient` ; DTO `NotificationResponse`.
- **Décisions :** **canaux** (in-app, email, push ?) ; synchrone vs file d'attente ; lecture/marquage.

### 4.10 `email` — modèles & envoi d'emails
- **Responsabilité :** gérer des modèles d'emails et déclencher leur envoi.
- **Composants :** `EmailTemplateController`, `EmailService` (`JavaMailSender`), `EmailTemplateRepository`, éventuel moteur de rendu.
- **Dépendances :** `reference` (variables), `notification`.
- **Entités/DTO :** `EmailTemplate`, éventuel `EmailOutbox`/journal d'envoi ; DTO `EmailTemplateRequest/Response`.
- **Décisions :** **fournisseur SMTP** & secrets ; **moteur de template** (Thymeleaf/Freemarker) ; journalisation des envois ; i18n des modèles.

---

## 5. Tableaux des API

### 5.1 APIs existantes (confirmées par le code)

| Méthode | Chemin | Description | Auth actuelle |
|---|---|---|---|
| POST | `/api/v1/users` | Créer un utilisateur (201 + `Location`) | aucune |
| GET | `/api/v1/users/{id}` | Récupérer un utilisateur | aucune |
| GET | `/api/v1/users` | Lister (paginé : `page`, `size`, `sort`) | aucune |
| PUT | `/api/v1/users/{id}` | Mettre à jour (email, firstName, lastName) | aucune |
| DELETE | `/api/v1/users/{id}` | Désactiver (logique, `INACTIVE`) | aucune |

> Ces endpoints **ne doivent pas être dupliqués** par les modules futurs.

### 5.2 APIs proposées (non implémentées — propositions)

> **Statut : proposition.** Aucune de ces routes n'existe. À valider avant développement.

**Roles & permissions**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/roles` | Lister les rôles | ADMIN |
| POST | `/api/v1/roles` | Créer un rôle | ADMIN |
| GET | `/api/v1/roles/{id}` | Détail d'un rôle | ADMIN |
| PUT | `/api/v1/roles/{id}` | Mettre à jour un rôle | ADMIN |
| DELETE | `/api/v1/roles/{id}` | Supprimer un rôle | ADMIN |
| PUT | `/api/v1/users/{id}/roles` | Affecter des rôles à un utilisateur | ADMIN |

**Profil (self-service)**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/me` | Profil de l'utilisateur connecté | USER |
| PUT | `/api/v1/me` | Mettre à jour son profil | USER |
| GET | `/api/v1/me/preferences` | Lire ses préférences | USER |
| PUT | `/api/v1/me/preferences` | Mettre à jour ses préférences | USER |

**Données de référence**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/reference/{type}` | Lister les valeurs d'une nomenclature | USER |
| GET | `/api/v1/reference/{type}/{code}` | Détail d'une valeur | USER |
| POST | `/api/v1/reference/{type}` | Créer une valeur | ADMIN |
| PUT | `/api/v1/reference/{type}/{code}` | Mettre à jour une valeur | ADMIN |
| DELETE | `/api/v1/reference/{type}/{code}` | Supprimer une valeur | ADMIN |

**Dashboard**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/dashboard/summary` | Indicateurs synthétiques | ADMIN, MANAGER |
| GET | `/api/v1/dashboard/statistics` | Séries statistiques (`from`, `to`, `groupBy`) | ADMIN, MANAGER |

**Reporting / exports**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/reports/users/export` | Export utilisateurs (`format=pdf\|xlsx\|csv`) | ADMIN, MANAGER |
| POST | `/api/v1/reports` | Générer un rapport asynchrone (optionnel) | ADMIN, MANAGER |
| GET | `/api/v1/reports/{id}` | Statut/résultat d'un rapport | ADMIN, MANAGER |

**Audit**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/audit-logs` | Consulter l'historique (filtres, pagination) | ADMIN |

**Notifications**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/notifications` | Notifications de l'utilisateur (paginé) | USER |
| GET | `/api/v1/notifications/{id}` | Détail | USER |
| PUT | `/api/v1/notifications/{id}/read` | Marquer comme lue | USER |
| POST | `/api/v1/notifications` | Émettre une notification (admin/système) | ADMIN |

**Emails**

| Méthode | Chemin | Description | Rôles proposés |
|---|---|---|---|
| GET | `/api/v1/email-templates` | Lister les modèles | ADMIN |
| GET | `/api/v1/email-templates/{id}` | Détail d'un modèle | ADMIN |
| POST | `/api/v1/email-templates` | Créer un modèle | ADMIN |
| PUT | `/api/v1/email-templates/{id}` | Mettre à jour un modèle | ADMIN |
| DELETE | `/api/v1/email-templates/{id}` | Supprimer un modèle | ADMIN |
| POST | `/api/v1/email-templates/{id}/test` | Envoyer un email de test | ADMIN |

**Conventions transverses proposées** : pagination `page`/`size`/`sort`, filtres en query params, erreurs `ProblemDetail`, autorisation par rôle (cf. §7).

---

## 6. Modèle de données conceptuel

> **Aucune migration n'est créée à ce stade.** Le modèle ci-dessous est une proposition.

### 6.1 Existant

**`app_user`** (implémenté)

| Champ | Type | Contraintes |
|---|---|---|
| id | UUID | PK |
| email | VARCHAR(320) | NOT NULL, UNIQUE (`uk_app_user_email`) |
| keycloak_id | VARCHAR(255) | UNIQUE (`uk_app_user_keycloak_id`), NULL autorisé |
| first_name | VARCHAR(100) | — |
| last_name | VARCHAR(100) | — |
| status | VARCHAR(20) | NOT NULL (`ACTIVE`/`INACTIVE`) |
| created_at | TIMESTAMPTZ | NOT NULL |
| updated_at | TIMESTAMPTZ | NOT NULL |

### 6.2 Proposé

**`role`**

| Champ | Type | Contraintes |
|---|---|---|
| id | UUID | PK |
| code | VARCHAR(50) | NOT NULL, UNIQUE (ex. `ADMIN`, `MANAGER`, `USER`) |
| name | VARCHAR(100) | NOT NULL |
| description | VARCHAR(255) | — |
| system | BOOLEAN | NOT NULL (rôle protégé/non supprimable) |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL |

**`permission`**

| Champ | Type | Contraintes |
|---|---|---|
| id | UUID | PK |
| code | VARCHAR(100) | NOT NULL, UNIQUE (ex. `user:read`, `role:manage`) |
| description | VARCHAR(255) | — |

**`user_role`** (jointure)

| Champ | Type | Contraintes |
|---|---|---|
| user_id | UUID | FK → `app_user(id)`, PK composite |
| role_id | UUID | FK → `role(id)`, PK composite |
| assigned_at | TIMESTAMPTZ | NOT NULL |

Index : `user_role(user_id)`, `user_role(role_id)` (déjà couverts par la PK selon l'ordre).

**`role_permission`** (jointure)

| Champ | Type | Contraintes |
|---|---|---|
| role_id | UUID | FK → `role(id)`, PK composite |
| permission_id | UUID | FK → `permission(id)`, PK composite |

**`user_preference`**

| Champ | Type | Contraintes |
|---|---|---|
| user_id | UUID | PK, FK → `app_user(id)` |
| locale | VARCHAR(10) | ex. `fr`, `en` |
| timezone | VARCHAR(50) | ex. `Europe/Paris` |
| email_notifications | BOOLEAN | NOT NULL, défaut selon décision produit |
| updated_at | TIMESTAMPTZ | NOT NULL |

**`reference_value`** *(si table générique retenue — cf. §10)*

| Champ | Type | Contraintes |
|---|---|---|
| id | UUID | PK |
| type | VARCHAR(100) | NOT NULL |
| code | VARCHAR(100) | NOT NULL |
| label | VARCHAR(255) | NOT NULL |
| sort_order | INT | NOT NULL, défaut 0 |
| active | BOOLEAN | NOT NULL, défaut true |
| created_at / updated_at | TIMESTAMPTZ | NOT NULL |

Contrainte d'unicité : `UNIQUE (type, code)` ; index `reference_value(type)`.

**`audit_log`**

| Champ | Type | Contraintes |
|---|---|---|
| id | UUID | PK |
| actor_user_id | UUID | FK → `app_user(id)` (NULL si système) |
| action | VARCHAR(100) | NOT NULL (ex. `USER_CREATE`, `USER_DEACTIVATE`) |
| entity_type | VARCHAR(100) | NOT NULL |
| entity_id | VARCHAR(100) | — |
| details | JSONB | — (avant/après, champs modifiés — cf. RGPD) |
| ip_address | VARCHAR(45) | — |
| created_at | TIMESTAMPTZ | NOT NULL (immuable) |

Index : `audit_log(created_at)`, `audit_log(actor_user_id)`, `audit_log(entity_type, entity_id)`, éventuellement GIN sur `details`.

**`notification`**

| Champ | Type | Contraintes |
|---|---|---|
| id | UUID | PK |
| recipient_user_id | UUID | FK → `app_user(id)` |
| type | VARCHAR(50) | NOT NULL |
| title | VARCHAR(255) | NOT NULL |
| message | TEXT | — |
| read | BOOLEAN | NOT NULL, défaut false |
| created_at | TIMESTAMPTZ | NOT NULL (immuable) |
| read_at | TIMESTAMPTZ | — |

Index : `notification(recipient_user_id, read)`, `notification(created_at)`.

**`email_template`**

| Champ | Type | Contraintes |
|---|---|---|
| id | UUID | PK |
| code | VARCHAR(100) | NOT NULL, UNIQUE |
| subject | VARCHAR(255) | NOT NULL |
| body | TEXT | NOT NULL |
| locale | VARCHAR(10) | NOT NULL (unicité possible `(code, locale)`) |
| active | BOOLEAN | NOT NULL, défaut true |
| updated_at | TIMESTAMPTZ | NOT NULL |

**Relations (conceptuel)**

```
app_user 1 ──< user_role >── 1 role
app_user 1 ──1 user_preference
role     1 ──< role_permission >── 1 permission
app_user 1 ──< audit_log        (acteur, nullable)
app_user 1 ──< notification     (destinataire)
reference_value (autonome, clé logique: type+code)
email_template  (autonome, clé logique: code+locale)
```

---

## 7. Matrice rôles / permissions proposée

> Rôles **proposés** (à valider). Les codes sont alignés avec les scopes Keycloak ou des rôles locaux selon §10.

**Rôles**

| Rôle | Intention |
|---|---|
| `ADMIN` | Administration complète (utilisateurs, rôles, référentiels, emails, audit) |
| `MANAGER` | Pilotage : dashboards, rapports/exports, lecture des utilisateurs |
| `USER` | Usage courant : son profil, ses notifications, données de référence |

**Matrice (proposition)**

| Ressource / action | USER | MANAGER | ADMIN |
|---|:--:|:--:|:--:|
| `GET /me`, `PUT /me`, préférences | ✅ | ✅ | ✅ |
| `GET /users` | ❌ | ✅ (lecture) | ✅ |
| `GET /users/{id}` | ❌ | ✅ | ✅ |
| `POST/PUT/DELETE /users` | ❌ | ❌ | ✅ |
| Gestion des rôles (`/roles`, affectations) | ❌ | ❌ | ✅ |
| Données de référence — lecture | ✅ | ✅ | ✅ |
| Données de référence — écriture | ❌ | ❌ | ✅ |
| Dashboard / statistiques | ❌ | ✅ | ✅ |
| Rapports / exports | ❌ | ✅ | ✅ |
| Notifications (les siennes) | ✅ | ✅ | ✅ |
| Émission de notification | ❌ | ❌ | ✅ |
| Modèles d'emails | ❌ | ❌ | ✅ |
| Consultation de l'audit | ❌ | ❌ | ✅ |

---

## 8. Stratégie de tests et de migrations

### 8.1 Stratégie de tests

| Niveau | Outils | Cible |
|---|---|---|
| Unitaire | JUnit 5 + Mockito (repo mocké) | Règles métier des services |
| Mapper | JUnit 5 | Traduction entité ↔ DTO (mapper manuel) |
| Tranche MVC | `@WebMvcTest` + `@MockitoBean` | Contrats HTTP, validation, `ProblemDetail` |
| Tranche JPA | `@DataJpaTest` + Testcontainers PostgreSQL 16 | Repositories, migrations Flyway, contraintes |
| Intégration | `@SpringBootTest` + Testcontainers | Câblage complet application |
| Sécurité | `spring-security-test` (`@WithMockUser`, JWT mocké) | Autorisations par rôle/permission |
| Architecture (optionnel) | ArchUnit | Frontières entre modules (pas de fuite d'entités) |

Exigences :
- **Préserver les 28 tests** existants.
- Un nouveau module = ses tests unitaires + tranche MVC + (si persistance) tranche JPA.
- Tests de sécurité systématiques par endpoint protégé (401/403 vs 200).
- Les tests JPA reposent sur **Testcontainers** (pas de H2) pour rester fidèles à PostgreSQL 16.

### 8.2 Stratégie de migrations

- **Flyway = source de vérité.** `V1__create_user.sql` reste **inchangé**.
- Nouvelles migrations **additives** : `V2__…`, `V3__…` (jamais de modification d'une migration déjà appliquée).
- Aucune opération destructive sans justification (drop/rename → migration en plusieurs temps : ajout, backfill, bascule, suppression différée).
- Contraintes : `ddl-auto=validate` doit rester satisfait après chaque migration.
- Nommage : `V<n>__<description_snake_case>.sql`.
- Seeds de données de référence via migrations idempotentes ou `R__` repeatable (à décider, §10).

### 8.3 OpenAPI / erreurs (transverse)

- Chaque nouveau contrôleur : `@Tag`, `@Operation`, `@ApiResponses` (statuts réels), DTO `@Schema`.
- Erreurs toujours en `ProblemDetail` ; réutiliser `GlobalExceptionHandler` + exceptions de base.
- Étendre le customizer OpenAPI (nettoyage des schémas) si de nouveaux types génériques apparaissent.
- **Ne documenter que le réel** : pas d'endpoint fictif dans la spec.

### 8.4 Observabilité

- Actuator : enrichir (métriques, health checks custom) selon besoin.
- Logs : format structuré (JSON) à décider.
- Tracing distribué : non justifié pour un monolithe (à réévaluer si découpage).

---

## 9. Ordre recommandé d'implémentation

| Ordre | Module | Justification |
|---|---|---|
| 0 | **Gel des décisions** (§10) | Éviter les refontes (rôles, pagination, secrets) |
| 1 | **`common/security` — Keycloak** | Transverse : conditionne toutes les autorisations |
| 2 | **`role`** | Dépend des rôles Keycloak/local (décision §10) |
| 3 | **`user` — extensions** | Réactivation, liaison `keycloakId`, filtrage (réutilise l'existant) |
| 4 | **`reference`** | Base pour dashboards/rapports/notifications |
| 5 | **`audit`** (+ `common/audit`) | À poser tôt pour tracer les modules suivants |
| 6 | **`profile`** | Faible risque, s'appuie sur `user` + sécurité |
| 7 | **`email`** | Prérequis du canal email des notifications |
| 8 | **`notification`** | Dépend de `profile` + `email` |
| 9 | **`dashboard`** | Agrège `user`/`role`/`audit` |
| 10 | **`reporting`** | Dépend de `user`/`reference`/`audit` ; choix de bibliothèque |

---

## 10. Questions et décisions à valider avant développement

1. **Rôles — source de vérité :** Keycloak (rôles de realm/client) ou base locale ? Synchronisation dans les deux sens ?
2. **Modèle d'autorisation :** RBAC simple (rôles) ou RBAC + permissions fines ? Rôles figés ou dynamiques ?
3. **Provisioning utilisateurs :** création locale puis liaison `keycloakId`, ou création d'abord dans Keycloak ? Qui possède le mot de passe ?
4. **API `/me` :** confirmer le choix (self-service) et les champs modifiables ; suppression/`PATCH` partiel.
5. **Pagination :** conserver `Page` Spring (contrat JSON actuel) ou introduire un `PaginatedResponse<T>` maison ? (impacte le contrat existant)
6. **`sort` invalide :** corriger en 400 (ajout d'un handler) ou laisser en 500 ? (change le comportement actuel)
7. **Données de référence :** table générique `reference_value` vs tables dédiées ? Qui administre ? Cache ?
8. **Dashboard :** définition **produit** des métriques et KPI (absente aujourd'hui) — indispensable avant tout code.
9. **Reporting :** bibliothèque PDF/Excel (iText/OpenPDF, Apache POI), formats supportés, génération synchrone vs asynchrone, stockage des fichiers.
10. **Notifications :** canaux (in-app, email, push), synchrone vs file d'attente, préférences par utilisateur.
11. **Email :** fournisseur SMTP & gestion des secrets, moteur de template (Thymeleaf/Freemarker), journalisation des envois, i18n.
12. **Audit :** périmètre des actions tracées, rétention, stockage des données personnelles (RGPD), immutabilité.
13. **Suppression :** physique vs logique par domaine ; règles de purge.
14. **Observabilité :** logs structurés, métriques métier, health checks custom ?
15. **i18n / multilingue :** nécessaire pour les messages d'erreur et les emails ?
16. **Environnements & secrets :** gestion des secrets (Keycloak, SMTP) hors développement local.
17. **Convention de nommage SQL** des index/contraintes pour les futures migrations.

---

## Annexe A — Fichiers consultés pour cet audit

**Code source (main) :** `SDigitalApplication`, `common/config/OpenApiConfig`, `common/exception/GlobalExceptionHandler`, `user/controller/UserController`, `user/service/UserService`, `user/repository/UserRepository`, `user/entity/User`, `user/entity/UserStatus`, `user/dto/CreateUserRequest`, `user/dto/UpdateUserRequest`, `user/dto/UserResponse`, `user/mapper/UserMapper`, `user/exception/UserNotFoundException`, `user/exception/EmailAlreadyUsedException`.

**Tests :** `UserControllerTest` (11), `UserServiceTest` (9), `UserRepositoryTest` (5), `UserMapperTest` (3), `UserTestData` (fixture).

**Ressources & configuration :** `application.yml`, `db/migration/V1__create_user.sql`, `pom.xml`, `docker-compose.yml`, `.gitignore`, sortie générée `/v3/api-docs`.

**Recherche docs/specs/tickets :** aucun fichier `.md`, `.txt`, `.adoc`, `.pdf`, `.docx`, `.xlsx` de spécification trouvé dans le dépôt.

## Annexe B — Note de non-modification

Cette étape n'a créé qu'un seul fichier : `docs/architecture/backend-architecture.md`. Aucun fichier Java, `pom.xml`, `application.yml`, migration Flyway ou schéma PostgreSQL n'a été modifié ; aucune route n'a changé ; les 28 tests restent valides.
