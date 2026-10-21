# Keycloak Role Management Plan Review (READ-ONLY) — Étape 4E.3

References: docs/architecture/keycloak-role-management-audit.md, docs/architecture/keycloak-role-management-implementation-plan.md  
Last commit: b747cab feat(user): add agency and function assignments  
Branch: main  
Status: READ-ONLY review

## 1) Permissions minimales (realm Admin REST)
- view-users: nécessaire pour lookup utilisateur Keycloak.
- manage-users: nécessaire pour lister/attribuer/retirer rôles realm.
Hypothèse: selon realm, peut suffire. Éviter realm-admin, manage-clients, manage-realm sans justification.

## 2) Résolution utilisateur local → Keycloak
Règle stricte:
1. Trouver User local par UUID
2. Exiger keycloakId non null/non blank (seul ce champ). Aucune recherche par email, aucun auto-provisioning
3. Résoudre via Admin API avec ce keycloakId

Comportements:
- keycloakId absent/blank → 422 Unprocessable Entity
- Keycloak introuvable (404 Admin) → 404 Not Found
- Compte Keycloak désactivé → à confirmer par El Mehdi (recommandation: bloquer par défaut, 403 générique)

## 3) Auto-protection & rôles privilégiés
- Interdire auto-attribution/auto-retrait de rôles privilégiés
- Comparer currentKeycloakSub (JWT) vs target.keycloakId
- Interdire auto-retrait ADMIN par défaut (politique restrictive)
- Ne pas exposer rôles client tant que non validés (distinguer realm vs client strictement)

## 4) Allowlist & configuration
- Allowlist stricte (uniquement rôles dans SDIGITAL_KEYCLOAK_ROLE_ALLOWLIST)
- Absente ou vide → fail-closed (refuser toutes opérations)
- Rôle non autorisé/invalide → 403 générique
- Admin client non configuré → fail-closed

## 5) Erreurs, timeouts, indisponibilité
- Pas de fuite de secrets/tokens/détails internes
- Mapping: 401/403, 404, 422, 409, 400, 503 (indisponible/timeout), 502 (réseau)
- Timeouts configurés, pas de retry pour POST/DELETE non-idempotents
- Messages génériques

## 6) Audit sans données sensibles
Enregistrer: timestamp UTC, actorSub/actorUserId, targetUserId, targetKeycloakId (hash/préfixe), action (ROLE_ASSIGN/ROLE_REMOVE/ROLES_GET), rolesAdded/rolesRemoved, outcome, reasonCode. Exclure: tokens, secrets, emails complets si évitables, stack traces, réponses brutes.

## 7) Bloquantes vs techniques
Bloquantes (El Mehdi): scope (realm vs client), allowlist exacte, politique auto-protection (dont dernier ADMIN), compte désactivé, audit rétention, comptes système protégés.
Techniques: structure, mapping erreurs, logique auto-protection, fail-closed, tests.

## 8) Checklist GO/NO-GO
GO: décisions métier écrites, client+permissions testés, allowlist non vide, politique auto-protection, audit défini, stratégie tests.
NO-GO: allowlist absente, pas d''auto-protection, rôles client sans validation, secrets dans code, recherche par email, provisioning, Admin non configuré autorisé par défaut.

## Risques résiduels
- Divergence rôles Keycloak/JWT (nécessite refresh token)
- Dépendance disponibilité Keycloak Admin
- Permissions Keycloak variables selon realm
- Risque élévation si allowlist mal configurée