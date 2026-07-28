# camunda-demo-identity-provider

## Running locally

Requires Java 21 and Maven 3.9+, plus a Postgres instance (or point `SPRING_DATASOURCE_URL` at
one).

```bash
cp .env.example .env
$EDITOR .env
set -a && source .env && set +a
mvn spring-boot:run
```

```bash
curl http://localhost:8080/auth/.well-known/openid-configuration
```

## Verifying

```bash
mvn -q verify
```
