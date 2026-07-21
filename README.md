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
6. **(WIP) When coupon is 'used'** - when clicked 'activate coupon' vs when payment has been finished