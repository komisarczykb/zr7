package me.bartoszkomisarczyk.zr7.integration;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base for integration tests: provisions a throwaway PostgreSQL container that reproduces the
 * production bootstrap - {@code db/init/01_roles.sql} creates the DDL-capable owner and the
 * DML-only runtime user that Flyway's V3 grants target - then runs Flyway against it and exposes
 * helpers to assert the database contract from the perspective of {@code coupon_user}.
 * <p>
 * The container is started once for the JVM and shared across all subclasses; Spring reuses the
 * single application context for tests with identical configuration, so the whole suite pays one
 * database start and one application boot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

    /** PostgreSQL SQLSTATE for {@code insufficient_privilege}. */
    protected static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    /** PostgreSQL SQLSTATE for {@code check_violation}. */
    protected static final String SQLSTATE_CHECK_VIOLATION = "23514";
    /** PostgreSQL SQLSTATE for {@code unique_violation}. */
    protected static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    /** PostgreSQL SQLSTATE for {@code foreign_key_violation}. */
    protected static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";

    @ServiceConnection
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18.4-alpine"))
            // Reproduces the production bootstrap (db/init/01_roles.sql mounted in docker-compose.yaml):
            // creates the DDL-capable owner and the DML-only runtime user Flyway's V3 grants target.
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/init/01_roles.sql"),
                    "/docker-entrypoint-initdb.d/01_roles.sql")
            .waitingFor(Wait.forSuccessfulCommand("pg_isready -U test -d test"))
            .withStartupTimeout(Duration.ofSeconds(60));

    static {
        postgreSQLContainer.start();
    }

    // --- helpers for asserting the database contract as coupon_user ---

    protected void assertDenied(String sql) {
        assertRejected(sql, SQLSTATE_INSUFFICIENT_PRIVILEGE);
    }

    protected void assertRejected(String sql, String expectedSqlState) {
        SQLException ex = assertThrows(SQLException.class, () -> executeUpdateAsAppUser(sql));
        assertEquals(expectedSqlState, ex.getSQLState(),
                "expected SQLSTATE " + expectedSqlState + " for: " + sql);
    }

    protected long insertCoupon(String code) throws SQLException {
        return insertCoupon(code, 5);
    }

    protected long insertCoupon(String code, int maxUsage) throws SQLException {
        try (Connection c = connectAsAppUser(); Statement s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO coupon (code, max_usage, country_code) VALUES ('" + code + "', " + maxUsage + ", 'PL')",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet rs = s.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    protected int executeUpdateAsAppUser(String sql) throws SQLException {
        try (Connection c = connectAsAppUser(); Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    protected Connection connectAsAppUser() throws SQLException {
        return DriverManager.getConnection(postgreSQLContainer.getJdbcUrl(), "coupon_user", "couponPass");
    }
}
