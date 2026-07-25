# zr7

## Tech Stack

- **Java** - 25 (25.0.3-tem)
- **Spring Boot** - 4.1.0
- **Database** - PostgreSQL 18.4 (`18.4-alpine` image)
- **Build tool** - Maven 3.9.16 (via Maven Wrapper)
- **Database migrations** - Flyway (via `spring-boot-starter-flyway`)

## Timezone handling

- Application and db enforces the same timezone usage by:
    - jvm flag: `-Duser.timezone=UTC`
    - hibernate: `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`
    - postgres container command: `-c timezone=UTC -c log_timezone=UTC`
    - postgres env settings: `TZ: UTC` and `PGTZ: UTC`

## Data model

Two tables, defined by the Flyway migrations:

- **coupon** - the coupon definition. `id` is an internal serial PK that never leaves the DB (coupons are activated by
  `code`, which is unique case-insensitively via a `UPPER(code)` index). `current_usage <= max_usage` and
  `max_usage > 0`
  are enforced by CHECK constraints; `country_code` is constrained to exactly two uppercase letters.
- **coupon_usage** - one row per redemption. The `(coupon_id, user_id)` UNIQUE constraint enforces single-use per
  customer; `used_at` is a `TIMESTAMPTZ`.

## Geolocation IP verification

Country restriction is resolved through a geolocation port.
The implementation is kept minimal as I don't consider it being most important part of the project and I'm running out
of time :)

- **Port** - `domain/geolocation/GeoLocationProvider` returns a `GeoLocationResult` (ISO-3166-1 alpha-2 country code as
  String) or throws `GeoLocationException`. Main coupon service depends on the port only.
- **Adapter** - `adapter/geolocation/ipapi` implements the port against the free [ip-api.com](https://ip-api.com/) JSON
  endpoint.
- **Provider** - `geolocation.provider` selects the active adapter (`ipapi` in this case). Both the adapter and its
  `RestClient` bean are gated by `@ConditionalOnProperty` but also set as `matchIfMissing = true` so the context boots
  cleanly when the property is absent;
- **HTTP client timeouts** - `connect-timeout: 3s`, `read-timeout: 5s`; a hanging upstream
  fails fast instead of holding a request thread.
- **ip-api default provider** - chosen because of the ease of use, api allows to get a lot of information but i;ve
  decided to limit the request with only 3 fields - status, message, countryCode. Few fields are language dependant, not
  in our case at the moment of creating the application but `lang=en` parameter has been added to enforce English
  language just in case. **HOWEVER, THE BIGGEST DOWNSIZE OF THIS PROVIDER IS 45REQUESTS/MINUTE LIMITATION FOR FREE TIER
  WHICH WE USE**
- **In-memory caching** - a `CachingGeoLocationProvider` decorator sits in front of the active provider. Repeated
  `resolve(ip)` calls for the same IP are served from a Caffeine cache, which directly helps with the 45 req/min limit.
  Tunable via `geolocation.cache.ttl` and `geolocation.cache.max-size`.

## Testing

Run with `./mvnw test`.

Integration tests run against PostgreSQL via Testcontainers that reproduces the expected db config;
`db/init/01_roles.sql` creates the DDL-capable owner and the DML-only user used by the app (based on assmuption that
external team manages db). Then Flyway applies `src/main/resources/db/migration` scripts and sets up tables and grants
permissions. All test classes share a single container and application context.

- **`AppContextIntegrationTest`** — the application boots and Flyway applies every migration against a fresh database.
- **`DbUserPermissionsTest`** — connects as `coupon_user` and asserts the least-privilege model: the granted DML is
  allowed, while DDL and destructive operations are denied.
- **`DbSchemaConstraintsTest`** — asserts the (expected) schema enforces the business rules for our user: the CHECK
  constraints, the case-insensitive uniqueness, the single-use constraint, and the `coupon_usage` foreign key.
- **`GeoLocationCachingIntegrationTest`** — asserts a second `resolve()` for the same IP is served from cache while the
  upstream is hit once.

## Decisions

1. **GraalVM Native Image** - Considered for cloud cost savings, but deferred to a *nice-to-have* priority in favor of
   delivering functionality and tests first. Mockito does not work well with native image, which would have complicated
   the test setup.
2. **Coupon primary key stays internal** - The coupon's database ID never leaves the database. Coupons are activated by
   their *code*, so the numeric primary key is never exposed or used as an external identifier.
3. **Country validation by IP address, not delivery address** - Per the task requirements, country validation is based
   solely on the request's IP address. The order's delivery address is not taken into account, even when it differs from
   the user's IP-based location.
4. **Manual Docker lifecycle management** - Chose to manage the database container lifecycle manually (e.g., via
   `docker compose`) rather than relying on Spring Boot's `spring-boot-docker-compose` module, where the application
   itself manages the database lifecycle. Manual control keeps the container lifecycle explicit and decoupled from the
   app.
5. **When coupon is 'used'** - the assumption is that 'coupon' is considered used at the moment, the request with the
   corresponding code reaches the db and is valid. No 'reserving' process will be implemented.
6. **Expected load** - Assumption is (based on the description of the job opening) that there is up to 3000 requests/s,
   scattered across the 200+ microservices. After testing the throughput on a simplified example i was able to process
   1300 requests/s which i consider acceptable in this project;
7. **Bottleneck** - Postgres in version 18.4 by default accepts up to 100 concurrent
   connections ([docs - max_connections](https://www.postgresql.org/docs/18/runtime-config-connection.html#GUC-MAX-CONNECTIONS))
   so without tuning the db itself this is the limit for all service instances. On top of that there should be some
   reserved connections for administrative tasks, those are omitted in this project.
8. **User authentication** — Not implemented; this is a deliberate assumption. The coupon service is treated as an
   internal service, called by the checkout service at payment confirmation, when the customer has already been
   identified upstream — either as a logged-in account or, for guest checkout, via one-time customer details. The
   request carries that identity as an opaque `customerRef`, which this service does not verify and uses only to enforce
   the one-redemption- per-customer rule.
9. **Flyway-managed schema with least-privilege DB users** - Schema changes are versioned and applied by Flyway using a
   dedicated DDL-capable user (`coupon_db_owner`), while the runtime application connects as `coupon_user`, which holds
   only the DML grants it needs (`SELECT/INSERT/UPDATE` on `coupon`, `SELECT/INSERT` on `coupon_usage`, plus sequence
   usage). Hibernate's `ddl-auto` is `none`, so the app never alters the schema at runtime - schema evolution happens
   only
   through reviewed, ordered migrations. This separates who can change the structure from who can touch the data, and
   keeps
   the runtime surface minimal (verified by `DbUserPermissionsTest`)
    - My general assumption is that DB schema is managed by other team/user than the one used by app, therefore
      separation of permissions;
    - `db/init/01_roles.sql` lives outside the migrations because Flyway cannot create the login it connects as — the
      `coupon_db_owner` role must already exist before Flyway runs. In production this is the external team's bootstrap;
      locally and in tests it is mounted into the container's `/docker-entrypoint-initdb.d`.
10. **Geolocation via the free ip-api.com endpoint** - chosen for the prototype because it needs no API key. The free
    tier is limited to **45 requests/min per IP** ([docs](https://ip-api.com/docs/api:json)), which is incompatible with
    the ~1300 req/s mentioned in [decision #6](#decisions). In 'real-world' scenario this adapter can be implemented
    using proper service, behind the same port so the domain is untouched.
11. **Caffeine cache + decorator in front of the port** - cut redundant *SUCCESSFUL* ip-api calls (the free tier's 45
    req/min, see [#10](#decisions)) without the domain knowing anything about caching: the decorator implements the same
    port and is`@Primary`.

## Discarded ideas

1. **Two tables in the database** - `coupon` (id pk, code, max_usage, country_code, created_at) and `coupon_usage`
   ((coupon_id, user_id) pk, used_at). An insert into `coupon_usage` guarantees uniqueness of usage per user; a prepared
   statement speeds up the insert operation, and a read replica verifies the usage count via an aggregate function.
   **Reason for rejection**:
    1) overselling - the read replica is updated asynchronously from the write DB with a delay;
    2) the task requirements state that every coupon should contain information about its current usage count, so I
       assume
       this should be a property of the coupon itself, which conflicts with the proposed design.
2. **Shared counter** - split max usage into X rows and increment one of those
   **Reason for rejection**: increases throughput but causes one shard to be depleted while max_usage is still not
   capped
3. **Database sharding per region** - separate databases per continent, with the coupon's assigned country code serving
   as the partition key.
   **Reason for rejection**: cross-shard routing and global limit handling under sharding were consciously left out of
   scope for this task.
4. **Slot table** - decomposes each coupon into one row per available use at creation time, so `max_usage = 1000`
   becomes 1000 claimable rows, each claimed atomically via `FOR UPDATE SKIP LOCKED`.
   **Reason for rejection**:
    - max_usage rows written at creation, and storage is paid upfront regardless of actual usage table size.
    - `current_uses` row becomes possible bottleneck - incrementing would recreate the hot row and negate the
      entire gain.
    - Measured throughput of the conditional UPDATE (~1300 requests/s on a single coupon) serves this usecase
      Rejected on measured headroom and spec fit;