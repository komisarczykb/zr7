# zr7

A coupon service: coupons are created with a country restriction and a global usage limit, then redeemed
(activated) at most once per user, with the redeeming user's country resolved from their IP address.

## Contents

- [Assumptions](#assumptions) - the constraints the design is built on; **start here**
- [Tech Stack](#tech-stack)
- [How to run](#how-to-run) - Docker only, one command
- [Timezone handling](#timezone-handling)
- [Data model](#data-model)
- [Coupon API](#coupon-api) - endpoints, the activation flow and its rejection ordering
- [Coupon caching](#coupon-caching)
- [Geolocation IP verification](#geolocation-ip-verification) - port, adapter, caching, circuit breaker
- [Testing](#testing)
- [Manual API verification (Postman / Bruno)](#manual-api-verification-postman--bruno)
- [Decisions](#decisions) - what was chosen and why
- [Discarded ideas](#discarded-ideas) - what was rejected and why

## Assumptions

These assumptions shape the code more than anything else in this README - most of the design below only
makes sense in their light. Each links to the [Decisions](#decisions) entry carrying the full reasoning.

- **The service is internal-only and its caller is already trusted** - it sits in the internal service
  mesh (e.g. behind checkout at payment confirmation), never on the public internet, and the user was
  authenticated upstream. → No authentication, authorization or transport security is implemented here,
  and `userId` is an opaque number this service does not verify - it only keys the one-redemption-per-user
  rule. See [decision #7](#decisions).
- **Country comes from the request IP, not the delivery address** - per the task requirements. → The
  activation flow calls a geolocation port and compares its ISO country code against the coupon's; a
  differing delivery address is not consulted anywhere. See [decision #3](#decisions).
- **The database schema is owned by a different team than the running app** - so the app's DB user is
  assumed to have no DDL rights. → Flyway connects as `coupon_db_owner` while the app connects as
  `coupon_user` (DML grants only), and `db/init/01_roles.sql` sits outside the migrations because it
  represents that external team's bootstrap. See [decision #8](#decisions).
- **A coupon is used the moment a valid activation commits** - there is no reserve/hold/confirm
  handshake and no compensating "release" step. → `insertUsage` + `incrementUsage` in one transaction is
  the whole redemption; a caller that fails after a `SUCCESS` cannot give the usage back.
  See [decision #5](#decisions).
- **Usage only ever grows** - no endpoint resets `current_usage`, raises `max_usage`, or cancels a
  redemption. → Exhaustion is a permanent fact, so `CouponExhaustionCache` can cache it without a
  staleness trade-off (its TTL bounds memory, not correctness). See [decision #11](#decisions).
- **Coupons are addressed by `code`, case-insensitively** - the numeric primary key is an internal
  detail. → The id never appears in a request or response; lookups uppercase the code and the DB enforces
  uniqueness through a `UPPER(code)` index. See [decision #2](#decisions).
- **Geolocation is prototype-grade and rate-limited** - the free ip-api.com tier allows 45 requests/min
  per IP, and a production deployment would swap in a paid provider behind the same port. → The caching
  decorator, negative caching and circuit breaker in front of the adapter exist primarily to stay inside
  that budget and to fail fast when it is exceeded. See decisions [#9](#decisions) and [#13](#decisions).
- **One Postgres instance, no DB-level tuning** - `max_connections` stays at the default 100 across all
  app instances. → That connection ceiling, not application code, is the assumed scaling bottleneck; the
  Hikari pool is sized conservatively (10) against it. See [decision #6](#decisions).

## Tech Stack

- **Java** - 25 (25.0.3-tem)
- **Spring Boot** - 4.1.0
- **Database** - PostgreSQL 18.4 (`18.4-alpine` image)
- **Build tool** - Maven 3.9.16 (via Maven Wrapper)
- **Database migrations** - Flyway (via `spring-boot-starter-flyway`)
- **Caching** - Caffeine (in-process caches for geolocation lookups and some coupon data)
- **Resilience** - Resilience4j 2.4.0 (`resilience4j-spring-boot4`) circuit breaker for the geolocation adapter
- **Observability** - Spring Boot Actuator (`/actuator/health`)
- **API documentation** - springdoc-openapi 3.0.3 (`springdoc-openapi-starter-webmvc-ui`), generating the OpenAPI 3
  spec and Swagger UI at runtime from the controller/DTO annotations
- **Testing** - JUnit 5 / Mockito (`spring-boot-starter-test`), Testcontainers 2.0.5 (PostgreSQL integration tests)

## How to run

**Prerequisites: Docker only.**

The `app` service lives behind the `full` Compose profile; `postgres` has no profile, so it always starts.

```
docker compose --profile full up --build
```

This builds the app image (multi-stage `Dockerfile`, JDK 25 only inside the build stage) and starts it alongside
Postgres; Flyway migrates the schema on boot. The API is then available at `localhost:8080` (Swagger UI at
`/swagger-ui/index.html`, health at `/actuator/health`).

For local development (running the app from an IDE or via `./mvnw spring-boot:run`), you additionally need a
local JDK 25 (Maven itself is bootstrapped by the wrapper) and only the database container - since `app` requires
the `full` profile, a plain `docker compose up` starts `postgres` only:

```
docker compose up -d
./mvnw spring-boot:run
```

`./mvnw test` runs the full test suite (JUnit + Testcontainers-backed integration tests) and needs Docker but not
`docker compose up` - Testcontainers manages its own throwaway Postgres container per test run.

### Tearing down

```
docker compose --profile full down
```

Stops and removes the `app` and `postgres` containers (and the default network Compose created for them). If you
only started the database (`docker compose up -d`, no `--profile full`), a plain `docker compose down` is
equivalent since `postgres` is the only service that would be running.

Postgres has no named volume - its data directory lives in the container's own writable layer, so `docker compose
down` (without `-v`) already discards it on the next `up`; there is no `-v` flag needed to reset the database, and
none to add if you actually wanted the data to persist across a teardown.

To also drop the built `app` image (forcing a rebuild on the next `up --build`):

```
docker compose --profile full down --rmi local
```

## Timezone handling

- Timestamps are timezone-independent by construction, not by configuration:
    - `creation_date` / `used_at` are `TIMESTAMPTZ`, so Postgres stores an absolute point in time
    - `creation_date` is read back via `ResultSet.getTimestamp(...).toInstant()`, which carries no
      offset, so the JVM's default timezone cannot shift the value on the way out; `used_at` is
      write-only today (set by the column's `DEFAULT now()` on insert) and is not mapped back into
      Java anywhere
- The Postgres container is additionally pinned to UTC so server-side output (logs, `now()`, psql
  sessions) is unambiguous:
    - container command: `-c timezone=UTC -c log_timezone=UTC`
    - env settings: `TZ: UTC` and `PGTZ: UTC`

## Data model

Two tables, defined by the Flyway migrations:

- **coupon** - the coupon definition. `id` is an internal serial PK that never leaves the DB (coupons are activated by
  `code`, which is unique case-insensitively via a `UPPER(code)` index). `max_usage > 0`, `current_usage >= 0` and
  `current_usage <= max_usage`
  are enforced by CHECK constraints; `country_code` is constrained to exactly two uppercase letters.
- **coupon_usage** - one row per redemption, with a `coupon_id` FK to `coupon`. The `(coupon_id, user_id)` UNIQUE
  constraint enforces single-use per customer; `used_at` is a `TIMESTAMPTZ`.

## Coupon API

The OpenAPI spec is generated at runtime by springdoc from the controller/DTO annotations (no hand-maintained
spec file, so it shouldn't drift from the actual endpoints): raw spec at `/v3/api-docs`, Swagger UI at
`/swagger-ui/index.html`. `CouponController` carries the `@Tag`/`@Operation`/`@ApiResponse` annotations per
endpoint (status codes, response/`ProblemDetail` schemas, and the `Retry-After` header on `503`), so the exact
response contract for every status below is also browsable/testable straight from Swagger UI.

- `POST /api/v1/coupons` - create a coupon. Body: `{code, maxUsage, countryCode}` (`code` is
  `@NotBlank`/max 16 chars, `maxUsage` is `@Positive`, `countryCode` is a two-letter `@Pattern`).
  Returns `201 CREATED` with a `CouponResponse` (`code, creationDate, maxUsage, currentUsage,
  countryCode` - the internal database id is never included, per decision #2), or `409 CONFLICT`
  with a `ProblemDetail` if `code` already exists (case-insensitively), or `400 BAD_REQUEST` with a
  `ProblemDetail` listing the failing fields.
- `GET /api/v1/coupons/{code}` - read a coupon back as the same `CouponResponse` shape. Returns
  `404 NOT_FOUND` with a `ProblemDetail` for an unknown code. `currentUsage` is always read live
  from the database (never from the lookup cache), so it reflects concurrent activations.
- `POST /api/v1/coupons/activate` - activate (redeem) a coupon. Body: `{code, userId, userIp}` (`code`
  and `userIp` are `@NotBlank`). Returns `{status}` where `status` is one of `SUCCESS` (`200`),
  `NOT_FOUND` (`404`), `COUNTRY_NOT_ALLOWED` (`403`), `ALREADY_USED` (`409`), `EXHAUSTED` (`409`), or
  `GEOLOCATION_UNAVAILABLE` (`503`, with a `Retry-After` header).
- **Activation flow** - `CouponService.activateCoupon` checks rejection reasons in a fixed order: (1)
  the coupon must exist (`NOT_FOUND`), (2) the caller's country, resolved from `userIp` via the
  geolocation port, must match (`COUNTRY_NOT_ALLOWED` / `GEOLOCATION_UNAVAILABLE`), (3) the exhaustion
  cache (see below) must be clear (`EXHAUSTED`), then `insertUsage` + `incrementUsage` run in a single
  transaction. This ordering makes `NOT_FOUND` and `COUNTRY_NOT_ALLOWED` deterministic for a given
  request and DB state regardless of cache warmth. `EXHAUSTED` vs `ALREADY_USED` is the one exception:
  the exhaustion cache is a per-JVM Caffeine cache (see below) with no cross-replica sync, so a coupon
  that is both exhausted and already redeemed by the same user can resolve to `EXHAUSTED` on a replica
  whose cache is warm and `ALREADY_USED` on a replica whose cache is cold. Both map to `409`, so the
  blast radius is limited to which rejection *reason* is reported, not the outcome. The usage row is
  inserted *before* the conditional `current_usage` update, so a duplicate activation is caught by the
  `(coupon_id, user_id)` unique constraint (`ALREADY_USED`), and an update that affects 0 rows (because
  `current_usage` already hit `max_usage`) rolls the whole transaction back and marks the code exhausted
  - this keeps `count(coupon_usage) == current_usage` under concurrent requests without needing
  row-level locking.
- **Fail-closed on geolocation failure** - if the geolocation provider cannot be reached, times out, or
  rate-limits us (`GeoLocationException`), the country cannot be determined, so the request is
  rejected as `GEOLOCATION_UNAVAILABLE` rather than being let through *or* reported as
  `COUNTRY_NOT_ALLOWED`. Conflating a provider outage with a policy decision would tell a legitimate
  customer their country is banned, and would give a client no way to tell "retry me" from "don't
  bother". The rate-limited case (`GeoLocationRateLimitedException`) gets a longer `Retry-After` (60s)
  than a generic failure (5s). Every occurrence is logged at `WARN`.

## Coupon caching

Two Caffeine-backed caches sit in front of the DB, following the same decorator-in-front-of-a-port
shape as `CachingGeoLocationProvider` below.

- **`CouponExhaustionCache`** (`domain/coupon`) - once `incrementUsage` affects 0 rows the code
  is marked exhausted, keyed by uppercased code. `current_usage` never decreases (no reset/limit-raise
  endpoint exists), so this fact is permanent - repeat activation attempts for a dead code skip the
  database entirely. Tunable via `coupon.exhaustion-cache.ttl` and `coupon.exhaustion-cache.max-size`.
- **`CachingCouponRepository`** (`adapter/out/coupon`) - a `@Primary` decorator around
  `JdbcCouponRepository` (now `@Qualifier("delegateCouponRepository")`) that caches
  `findLookupByCode`, the `(id, countryCode)` projection that never changes after creation; `findByCode` is deliberately
  pass-through, because it returns live `currentUsage` (see Coupon API). Tunable via
  `coupon.lookup-cache.ttl` and `coupon.lookup-cache.max-size`.

## Geolocation IP verification

Country restriction is resolved through a geolocation port; the implementation is kept minimal.

- **Port** - `domain/geolocation/GeoLocationProvider` returns a `GeoLocationResult` (ISO-3166-1 alpha-2 country code as
  String) or throws `GeoLocationException`. Main coupon service depends on the port only.
- **Adapter** - `adapter/out/geolocation/ipapi` implements the port against the free [ip-api.com](https://ip-api.com/) JSON
  endpoint.
- **Provider** - `geolocation.provider` selects the active adapter (`ipapi` in this case). Both the adapter and its
  `RestClient` bean are gated by `@ConditionalOnProperty` but also set as `matchIfMissing = true` so the context boots
  cleanly when the property is absent.
- **HTTP client timeouts** - `spring.http.client.connect-timeout: 3s`, `spring.http.client.read-timeout: 5s`; a hanging
  upstream fails fast instead of holding a request thread.
- **ip-api default provider** - chosen because of the ease of use, api allows to get a lot of information but I've
  decided to limit the request with only 4 fields (`fields=57346`) - status, message, countryCode, query. On a
  successful lookup only status, countryCode and query (the looked-up IP) are present; on a `fail` status there is no
  countryCode, and message carries the failure reason instead. A few fields are language dependent, not
  in our case at the moment of creating the application but `lang=en` parameter has been added to enforce English
  language just in case. **HOWEVER, THE BIGGEST DOWNSIDE OF THIS PROVIDER IS 45 REQUESTS/MINUTE LIMITATION FOR FREE TIER
  WHICH WE USE**
- **In-memory caching** - a `CachingGeoLocationProvider` decorator sits in front of the active provider. Repeated
  `resolve(ip)` calls for the same IP are served from a Caffeine cache, which directly helps with the 45 req/min limit.
  Tunable via `geolocation.cache.ttl` and `geolocation.cache.max-size`.
- **Negative caching** - failures are cached too, under their own much shorter TTL
  (`geolocation.cache.negative-ttl`, 30s), so an unhealthy or throttling provider is not re-hit once per request. The
  cached exception is replayed as a fresh instance of the same concrete type, because the type is what decides the
  `Retry-After` the caller is given (60s for rate limiting, 5s otherwise).
- **Circuit breaker** - `resolve` is annotated `@CircuitBreaker(name = "ipapi")` (resilience4j, configured under
  `resilience4j.circuitbreaker.instances.ipapi`). Once half of the last 20 calls fail the circuit opens for 10s and
  further calls are rejected in microseconds instead of occupying a request thread for the 5s read timeout. An open
  circuit surfaces through a fallback as an ordinary `GeoLocationException`, so the HTTP contract is unchanged
  (`503 GEOLOCATION_UNAVAILABLE`). `GeoLocationUnresolvableException` - the provider answering normally that an IP is
  private, reserved or malformed - is listed under `ignore-exceptions`: bad input must not open the circuit for
  everyone else.

## Testing

Run with `./mvnw test`.

Integration tests run against PostgreSQL via Testcontainers that reproduces the expected db config;
`db/init/01_roles.sql` creates the DDL-capable owner and the DML-only user used by the app (based on the assumption
that an external team manages the db). Then Flyway applies `src/main/resources/db/migration` scripts and sets up tables and grants
permissions. All test classes share a single container; nearly all share a single application context — the exception is
`GeoLocationCircuitBreakerIntegrationTest`, which overrides `@SpringBootTest` properties (dead endpoint, lowered
thresholds) and so gets its own cached context.

- **`AppContextIntegrationTest`** — the application boots and Flyway applies every migration against a fresh database.
- **`DbUserPermissionsIntegrationTest`** — connects as `coupon_user` and asserts the least-privilege model: the granted
  DML is allowed, while DDL and destructive operations are denied.
- **`DbSchemaConstraintsIntegrationTest`** — asserts the (expected) schema enforces the business rules for our user:
  the CHECK constraints, the case-insensitive uniqueness, the single-use constraint, and the `coupon_usage` foreign key.
- **`GeoLocationCachingIntegrationTest`** — asserts a second `resolve()` for the same IP is served from cache while the
  upstream is hit once, and that a *failed* lookup is cached the same way: the repeat never reaches the provider, and
  the replayed exception keeps its concrete type, message and original cause.
- **`GeoLocationCircuitBreakerIntegrationTest`** — points the adapter at a dead endpoint so the breaker sees real
  transport failures (a mocked delegate would bypass the AOP aspect and prove nothing): asserts the adapter is
  proxied, that repeated failures open the circuit, that an open circuit is rejected without a network call and
  surfaces as `GeoLocationException`, and that unresolvable-IP failures are ignored by the breaker.
- **`CouponActivationIntegrationTest`** — covers the activation status matrix (not found, country
  mismatch, already-used, exhausted, success) and asserts no writes happen on the rejected paths;
  also asserts the full HTTP status/body-code contract for each of those five outcomes plus `400` on
  blank/missing request fields, and that the geolocation delegate is hit once per distinct IP even
  when reached through the real `CouponService`.
- **`CouponConcurrencyIntegrationTest`** — concurrent activation by distinct users respects
  `max_usage`, concurrent activation by the same user succeeds exactly once, and an exhausted coupon
  leaves no orphan `coupon_usage` row.
- **`CouponLookupCachingIntegrationTest`** — asserts the injected `CouponRepository` is the caching
  decorator, and that a repeated or differently-cased lookup for the same code hits the delegate once.
- **`CouponCreationIntegrationTest`** — the create/read HTTP contract: `201` on success without the
  database id on the wire, `409` on a case-insensitive duplicate code, `400` on each invalid field, and
  that `GET /api/v1/coupons/{code}` round-trips all five required fields (also case-insensitively) or
  `404`s for an unknown code.
- **`CouponServiceTest`** (`application/coupon`, not an integration test — no Testcontainers/Docker) —
  every `CouponCreationResult` and `CouponUsageResult` branch of `CouponService`, driven through
  Mockito mocks of `CouponRepository`, `PlatformTransactionManager`, `GeoLocationProvider` and
  `CouponExhaustionCache` — including that a failed conditional increment calls
  `setRollbackOnly()` and marks the coupon exhausted, while a successful one never rolls back. Runs
  in milliseconds and exists so the business-rule branching doesn't need a container to exercise; the
  integration tests above remain the source of truth for real database behaviour.

## Manual API verification (Postman / Bruno)

A ready-to-import [Bruno](https://www.usebruno.com/) collection lives at `bruno/` (open the folder
in Bruno, select the `Local` environment). It mirrors the scenarios below, in the same run order,
with assertions on status code and response body already wired up per request. The curl commands
below are the same scenarios for manual use or for pasting into Postman ("Import" → raw text/cURL).

All commands assume the app is reachable at `http://localhost:8080` (`docker compose --profile full
up --build`, or `docker compose up -d` + `./mvnw spring-boot:run`).

### 0. Sanity checks

```bash
# Health
curl -i http://localhost:8080/actuator/health

# OpenAPI spec / Swagger UI reachable
curl -i http://localhost:8080/v3/api-docs
```

<details>
<summary><b>Full curl walkthrough — scenarios 1–13</b> (click to expand)</summary>

### 1. Create coupon — success (201)

```bash
curl -i -X POST http://localhost:8080/api/v1/coupons \
  -H "Content-Type: application/json" \
  -d '{"code":"SUMMER2026","maxUsage":100,"countryCode":"PL"}'
```
Expect `201`, body has `code, creationDate, maxUsage, currentUsage=0, countryCode` (no `id`).

### 2. Create coupon — duplicate code, case-insensitive (409)

```bash
curl -i -X POST http://localhost:8080/api/v1/coupons \
  -H "Content-Type: application/json" \
  -d '{"code":"summer2026","maxUsage":50,"countryCode":"PL"}'
```
Expect `409` with `ProblemDetail`.

### 3. Create coupon — validation errors (400)

```bash
# blank code
curl -i -X POST http://localhost:8080/api/v1/coupons \
  -H "Content-Type: application/json" \
  -d '{"code":"","maxUsage":10,"countryCode":"PL"}'

# non-positive maxUsage
curl -i -X POST http://localhost:8080/api/v1/coupons \
  -H "Content-Type: application/json" \
  -d '{"code":"BADQTY","maxUsage":0,"countryCode":"PL"}'

# invalid countryCode (not 2 letters)
curl -i -X POST http://localhost:8080/api/v1/coupons \
  -H "Content-Type: application/json" \
  -d '{"code":"BADCC","maxUsage":10,"countryCode":"POL"}'

# code over 16 chars
curl -i -X POST http://localhost:8080/api/v1/coupons \
  -H "Content-Type: application/json" \
  -d '{"code":"THISCODEISWAYTOOLONG","maxUsage":10,"countryCode":"PL"}'
```
Each should return `400` with a `ProblemDetail` listing the failing field.

### 4. Get coupon — success (200) / not found (404)

```bash
curl -i http://localhost:8080/api/v1/coupons/SUMMER2026
curl -i http://localhost:8080/api/v1/coupons/summer2026   # case-insensitive lookup

curl -i http://localhost:8080/api/v1/coupons/DOESNOTEXIST
```

### 5. Activate — pick a real IP and confirm its country first

The country check calls the real `ip-api.com`, so confirm what country your test IP resolves to
before asserting `SUCCESS` vs `COUNTRY_NOT_ALLOWED`:

```bash
curl -s "http://ip-api.com/json/8.8.8.8?fields=57346&lang=en"
```
`8.8.8.8` (Google DNS) is stable and resolves to `US`. Create a matching coupon:

```bash
curl -i -X POST http://localhost:8080/api/v1/coupons \
  -H "Content-Type: application/json" \
  -d '{"code":"USONLY","maxUsage":2,"countryCode":"US"}'
```

### 6. Activate — success (200)

```bash
curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"USONLY","userId":1001,"userIp":"8.8.8.8"}'
```
Expect `{"status":"SUCCESS"}`, `200`. Confirm via GET that `currentUsage` incremented:
```bash
curl -i http://localhost:8080/api/v1/coupons/USONLY
```

### 7. Activate — already used, same user (409)

```bash
curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"USONLY","userId":1001,"userIp":"8.8.8.8"}'
```
Expect `{"status":"ALREADY_USED"}`, `409`.

### 8. Activate — country not allowed (403)

Reuse `USONLY` (country `US`) with an IP resolving elsewhere, e.g. `1.1.1.1`:
```bash
curl -s "http://ip-api.com/json/1.1.1.1?fields=57346&lang=en"   # check its country first

curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"USONLY","userId":2002,"userIp":"1.1.1.1"}'
```
Expect `{"status":"COUNTRY_NOT_ALLOWED"}`, `403`.

### 9. Activate — exhausted (409)

`USONLY` has `maxUsage:2`; one successful usage already happened in step 6. Consume the last slot,
then try to exceed it:
```bash
# 2nd successful redemption (different user, same US IP) -> fills maxUsage
curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"USONLY","userId":3003,"userIp":"8.8.8.8"}'

# 3rd attempt, new user -> exhausted
curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"USONLY","userId":4004,"userIp":"8.8.8.8"}'
```
Expect `{"status":"EXHAUSTED"}`, `409` on the last call.

### 10. Activate — coupon not found (404)

```bash
curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"NOSUCHCODE","userId":1,"userIp":"8.8.8.8"}'
```

### 11. Activate — geolocation unavailable (503)

Private/reserved IPs make `ip-api` return a `fail` status, which the service treats as
`GeoLocationException` → fail-closed:
```bash
curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"SUMMER2026","userId":1,"userIp":"10.0.0.1"}'
```
Expect `503`, `{"status":"GEOLOCATION_UNAVAILABLE"}`, and a `Retry-After` header (`5` for generic
failure, `60` if the real 45 req/min rate limit was hit).

### 12. Activate — validation errors (400)

```bash
curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"","userId":1,"userIp":"8.8.8.8"}'

curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"SUMMER2026","userId":null,"userIp":"8.8.8.8"}'

curl -i -X POST http://localhost:8080/api/v1/coupons/activate \
  -H "Content-Type: application/json" \
  -d '{"code":"SUMMER2026","userId":1,"userIp":""}'
```

### 13. Caching behavior spot-checks

```bash
# Repeat GET of the same coupon lookup — lookup cache serves country/id, but currentUsage stays live
curl -i http://localhost:8080/api/v1/coupons/SUMMER2026
curl -i http://localhost:8080/api/v1/coupons/SUMMER2026

# Actuator health check
curl -s http://localhost:8080/actuator/health | jq
```

</details>

Notes:
- Because geolocation hits the real free `ip-api.com` (45 req/min), avoid hammering scenarios 5–11
  in a tight loop — it will trip the rate limit / circuit breaker unintentionally.
- An IP's reported country can drift over time or by provider, so the pre-check curl in step 8 (or
  the "Reference - Check IP Country" request in the Bruno collection) exists to keep the test
  deterministic — swap in whatever IP+country pair `ip-api` actually returns for you.

## Decisions

1. **GraalVM Native Image** - Considered for cloud cost savings, but deferred to a *nice-to-have* priority in favor of
   delivering functionality and tests first. Mockito does not work well with native image, which would have complicated
   the test setup.
2. **Coupon primary key stays internal** - The coupon's database ID never leaves the database. Coupons are activated by
   their *code*, so the numeric primary key is never exposed or used as an external identifier.
3. **Country validation by IP address, not delivery address** - Per the task requirements, country validation is based
   solely on the request's IP address. The order's delivery address is not taken into account, even when it differs from
   the user's IP-based location.
4. **Whole stack dockerized, lifecycle managed via `docker compose`, not `spring-boot-docker-compose`** - Both the
   database and the app itself are defined as `docker compose` services (the app via a multi-stage `Dockerfile`), so
   `docker compose up --build` is enough to run the project with no local JDK/Maven install. Spring Boot's
   `spring-boot-docker-compose` module (where the *app process* starts/stops its own DB container) was deliberately
   not used - it couples container lifecycle to the JVM starting up, which local dev (IDE run, `./mvnw
   spring-boot:run` against an already-running DB) does not want. Keeping `docker compose` as the single, explicit
   entry point for lifecycle management works for both the "just run it" case and local development: `app` sits
   behind the `full` Compose profile, so a plain `docker compose up` starts only `postgres` and the app runs outside
   a container, while `docker compose --profile full up --build` starts both.
5. **When coupon is 'used'** - the assumption is that 'coupon' is considered used at the moment, the request with the
   corresponding code reaches the db and is valid. No 'reserving' process will be implemented.
6. **Bottleneck** - Postgres in version 18.4 by default accepts up to 100 concurrent
   connections ([docs - max_connections](https://www.postgresql.org/docs/18/runtime-config-connection.html#GUC-MAX-CONNECTIONS))
   so without tuning the db itself this is the limit for all service instances. On top of that there should be some
   reserved connections for administrative tasks, those are omitted in this project.
7. **Internal-only service, no internet exposure** — This service is not exposed to the public internet; it is part of
   the internal service mesh and can only be reached by other internal services (e.g. the checkout service at payment
   confirmation). It is assumed that by the time a request reaches this service, the user has already been
   authenticated and authorized upstream — either as a logged-in account or, for guest checkout, via one-time customer
   details — and that the request carries all the data this service needs. That identity travels in the request as an
   opaque `userId`, which this service does not verify and uses only to enforce the one-redemption-per-user
   rule. The internal-trust boundary is why no user authentication, authorization, or transport-level security toward
   this service is implemented here.
8. **Flyway-managed schema with least-privilege DB users** - Schema changes are versioned and applied by Flyway using a
   dedicated DDL-capable user (`coupon_db_owner`), while the runtime application connects as `coupon_user`, which holds
   only the DML grants it needs (`SELECT/INSERT/UPDATE` on `coupon`, `SELECT/INSERT` on `coupon_usage`, plus sequence
   usage). The app has no ORM on the classpath (it talks to Postgres through `JdbcClient`), so it has no
   schema-altering capability at runtime — schema evolution happens only
   through reviewed, ordered migrations. This separates who can change the structure from who can touch the data, and
   keeps
   the runtime surface minimal (verified by `DbUserPermissionsIntegrationTest`)
    - My general assumption is that DB schema is managed by other team/user than the one used by app, therefore
      separation of permissions;
    - `db/init/01_roles.sql` lives outside the migrations because Flyway cannot create the login it connects as — the
      `coupon_db_owner` role must already exist before Flyway runs. In production this is the external team's bootstrap;
      locally and in tests it is mounted into the container's `/docker-entrypoint-initdb.d`.
9. **Geolocation via the free ip-api.com endpoint** - chosen for the prototype because it needs no API key. The free
   tier is limited to **45 requests/min per IP** ([docs](https://ip-api.com/docs/api:json)). In 'real-world' scenario this adapter can be implemented
   using proper service, behind the same port so the domain is untouched.
10. **Caffeine cache + decorator in front of the port** - cut redundant *SUCCESSFUL* ip-api calls (the free tier's 45
    req/min, see [#9](#decisions)) without the domain knowing anything about caching: the decorator implements the same
    port and is `@Primary`.
11. **Coupon exhaustion is cached permanently, not just TTL-bounded** - once a code is observed
    exhausted it stays that way forever (no reset/limit-raise endpoint exists), so caching it isn't an
    optimization with a correctness trade-off the way the geolocation cache's TTL is - the TTL on
    `CouponExhaustionCache` only bounds memory usage, not staleness.
12. **Coupon lookup caching reuses the geolocation decorator pattern** - `CachingCouponRepository` is
    `@Primary` in front of a qualified JDBC delegate, keeping the caching concern out of
    `JdbcCouponRepository` and `CouponService`, consistent with [decision #10](#decisions).
13. **Circuit breaker *and* negative caching in front of ip-api** - the two solve different problems and
    neither is sufficient alone. Negative caching is per IP, so it only helps when the *same* IP is asked for
    repeatedly; a 429 is caused by total request volume, so under throttling every distinct IP would still get
    one call through and keep the provider at its limit - which is what earns the documented one-hour ban. The
    circuit breaker is the global protection: it stops calling the provider entirely once the failure rate
    crosses the threshold, and it is also what keeps an upstream outage from parking every request thread on a
    5s read timeout. Two deliberate simplifications: an open circuit reports `Retry-After: 5` even when it was
    429s that opened it, because the open state does not carry the reason and threading that through the domain
    is not worth it; and the country check runs before the exhaustion check
    (see [Coupon API](#coupon-api)), so an activation for an already-exhausted code still spends a
    geolocation lookup - a deterministic rejection reason was judged worth more than the saved call, and the
    caches absorb most of the cost.

## Discarded ideas

1. **Usage count via read-replica aggregate, no counter column** - `coupon` (id pk, code, max_usage, country_code,
   creation_date) and `coupon_usage` ((coupon_id, user_id) pk, used_at), without a `current_usage` column on `coupon`
   itself. An insert into `coupon_usage` guarantees uniqueness of usage per user; a prepared statement speeds up the
   insert operation, and a read replica verifies the usage count via an aggregate function.
   **Reason for rejection**:
    1) overselling - the read replica is updated asynchronously from the write DB with a delay;
    2) the task requirements state that every coupon should contain information about its current usage count, so this
       should be a property of the coupon itself, which conflicts with the proposed design.
2. **Shared counter** - split max usage into X rows and increment one of those.
   **Reason for rejection**: increases throughput, but a single shard can be exhausted before the coupon's overall
   `max_usage` is reached, causing false `EXHAUSTED` rejections while capacity remains in the other shards.
3. **Database sharding per region** - separate databases per continent, with the coupon's assigned country code serving
   as the partition key.
   **Reason for rejection**: cross-shard routing and global limit handling under sharding were consciously left out of
   scope for this task.
4. **Slot table** - decomposes each coupon into one row per available use at creation time, so `max_usage = 1000`
   becomes 1000 claimable rows, each claimed atomically via `FOR UPDATE SKIP LOCKED`.
   **Reason for rejection**:
    - max_usage rows written at creation, and storage is paid upfront regardless of actual usage table size.
    - `current_usage` row becomes possible bottleneck - incrementing would recreate the hot row and negate the
      entire gain.
