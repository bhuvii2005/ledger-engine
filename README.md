# Ledger Engine

A double-entry financial ledger built with Java 21, Spring Boot 3.5, PostgreSQL 17 and Redis 7.

Every money movement (deposit, withdrawal or transfer) is one database transaction that writes a balanced pair of ledger entries: a debit on one account and a credit on the other. Amounts are stored as whole paise in `BIGINT` columns, so no floating-point arithmetic is involved. Accounts are locked in a fixed order (ascending id) so concurrent transfers can't deadlock. Every money-moving request needs an `Idempotency-Key` header, so a retried request is applied once and replays the original response. Postgres triggers make the ledger tables append-only and reject an unbalanced transaction at commit.

## Status

The core posting engine is done. The Redis idempotency cache, reversals, account statements, reconciliation, load tests and design docs are in progress.

## Running locally

Requires Docker.

```
docker compose up -d --build
curl localhost:8080/actuator/health
```

Create two accounts, deposit into one, then transfer between them:

```
curl -X POST localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"Ravi Kumar"}'

curl -X POST localhost:8080/api/v1/transactions/deposit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposit-0001" \
  -d '{"accountNumber":"ACC1000000001","amount":"5000.00","description":"salary"}'

curl -X POST localhost:8080/api/v1/transactions/transfer \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: transfer-0001" \
  -d '{"fromAccount":"ACC1000000001","toAccount":"ACC1000000002","amount":"1000.00","currency":"INR"}'
```

Sending the same request again with the same `Idempotency-Key` returns the original response with `Idempotent-Replayed: true` and does not move money twice. Amounts are JSON strings with at most two decimals.

The API docs are at http://localhost:8080/swagger-ui.html.

The database credentials are local development defaults (`ledger` / `ledger`). Override them with the `DB_USER`, `DB_PASSWORD` and `DB_NAME` environment variables.

Stop the stack with `docker compose down`, or `docker compose down -v` to also delete the database volume.

## Running tests

Requires JDK 21 and a running Docker daemon. The integration and concurrency tests start real PostgreSQL and Redis containers with Testcontainers.

```
./mvnw verify
```

This runs the unit tests, the API integration tests and the concurrency tests (double-spend, opposite-direction transfers, a random transfer mesh, and many concurrent requests with the same idempotency key). It also writes a JaCoCo coverage report to `target/site/jacoco/index.html`.

## License

MIT
