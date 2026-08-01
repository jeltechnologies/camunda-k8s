# keycunda

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

## License

Licensed under the Apache License, Version 2.0 - see [LICENSE](LICENSE) for the full text and the
disclaimer regarding support/warranty.
