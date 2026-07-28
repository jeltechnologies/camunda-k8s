# camunda-demo-identity-provider

**Demo-grade, not enterprise-grade.** This is a minimal OIDC provider built for a single-box demo
install — it does not aim for the security posture of an enterprise IdP (no MFA, no audit log, no
key rotation/HSM, no rate limiting, single in-memory signing key). Do not point it at a real
production tenant or treat it as a security boundary beyond what's documented below.

Minimal Spring Boot OIDC identity provider that replaces Keycloak in this stack. Built with
Spring Boot 4.1 / Spring Security 7's `spring-boot-starter-oauth2-authorization-server` (the
former standalone Spring Authorization Server project, now merged into Spring Security). Plain
JDBC (no JPA/Hibernate), records instead of Lombok, Thymeleaf for the two screens (`/login`,
`/admin/users`).

It provides exactly two things:

- Standards-compliant OIDC endpoints (`/.well-known/openid-configuration`, `/oauth2/authorize`,
  `/oauth2/token`, `/oauth2/jwks`, `/userinfo`) for the fixed set of Camunda component clients
  (`camunda-identity`, `orchestration`, `optimize`, `web-modeler`, `console`).
- A login screen and an admin screen to add/remove users and reset passwords.

Camunda's own authorization/role management (the `identity` Helm component) is unaffected — this
component only replaces *authentication*.

## Running locally

Requires Java 21 and Maven 3.9+, plus a Postgres instance (or point `SPRING_DATASOURCE_URL` at
one).

Secrets are kept in a gitignored `.env` file, never committed. Copy the template and edit it:

```bash
cp .env.example .env
$EDITOR .env
```

Then load it into your shell and run the app:

```bash
set -a && source .env && set +a
mvn spring-boot:run
```

Then check:

```bash
curl http://localhost:8080/auth/.well-known/openid-configuration
```

## Verifying

```bash
mvn -q verify
```

## Known limitations (by design, for a single-instance demo box)

- The JWT signing key is regenerated on every restart (in-memory `JWKSource`) — a restart
  invalidates outstanding tokens; users just log in again.
- The OAuth2 client list (`OidcClientsConfig`) is fixed in code, not a database table — adding a
  new Camunda component client is a code change, not an admin-screen action.
