# zr7

## Tech Stack

- **Java** - 25 (25.0.3-tem)
- **Spring Boot** - 4.1.0
- **Database** - PostgreSQL 18.4 (`18.4-alpine` image)
- **Build tool** - Maven 3.9.16 (via Maven Wrapper)

## Decisions

1. **GraalVM Native Image** - Considered for cloud cost savings, but deferred to a *nice-to-have* priority in favor of delivering functionality and tests first. Mockito does not work well with native image, which would have complicated the test setup.
2. **Coupon primary key stays internal** - The coupon's database ID never leaves the database. Coupons are activated by their *code*, so the numeric primary key is never exposed or used as an external identifier.
3. **Country validation by IP address, not delivery address** - Per the task requirements, country validation is based solely on the request's IP address. The order's delivery address is not taken into account, even when it differs from the user's IP-based location.
4. **Database sharding per region** - Considered a scenario with separate databases per continent, where the coupon's assigned country code would serve as the partition key. Cross-shard routing and global limit handling under sharding were consciously left out of scope for this task.
5. **Manual Docker lifecycle management** - Chose to manage the database container lifecycle manually (e.g., via `docker compose`) rather than relying on Spring Boot's `spring-boot-docker-compose` module, where the application itself manages the database lifecycle. Manual control keeps the container lifecycle explicit and decoupled from the app.
6. **When coupon is 'used'** - the assumption is that 'coupon' is considered used at the moment, the request with the corresponding code reaches the db and is valid. No 'reserving' process will be implemented.
7. **Expected load** - Assumption is (based on the description of the job opening) that there is up to 3000 requests/s, scattered across the 200+ microservices but i consider this a good minimal requirement for a single service in this case;
8. **Bottleneck** - Postgres in version 18.4 by default accepts up to 100 concurrent connections ([docs - max_connections](https://www.postgresql.org/docs/18/runtime-config-connection.html#GUC-MAX-CONNECTIONS)) so without tuning the db itself this is the limit for all service instances. On top of that there should be some reserved connections for administrative tasks, those are omitted in this project.