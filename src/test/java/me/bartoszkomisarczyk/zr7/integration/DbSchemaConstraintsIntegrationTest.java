package me.bartoszkomisarczyk.zr7.integration;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Schema invariants: the database rejects data that violates the business rules. These run as
 * {@code coupon_user} on purpose - the rules hold even for our own app user, because they are
 * enforced by the schema rather than application code.
 */
class DbSchemaConstraintsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void couponRejectsNonPositiveMaxUsage() {
        assertRejected("INSERT INTO coupon (code, max_usage, country_code) VALUES ('MAX0', 0, 'PL')",
                SQLSTATE_CHECK_VIOLATION);
    }

    @Test
    void couponRejectsNegativeCurrentUsage() {
        assertRejected("INSERT INTO coupon (code, max_usage, current_usage, country_code) "
                + "VALUES ('NEG1', 5, -1, 'PL')", SQLSTATE_CHECK_VIOLATION);
    }

    @Test
    void couponRejectsUsageExceedingMax() {
        assertRejected("INSERT INTO coupon (code, max_usage, current_usage, country_code) "
                + "VALUES ('OVR1', 1, 2, 'PL')", SQLSTATE_CHECK_VIOLATION);
    }

    @Test
    void couponRejectsInvalidCountryCode() {
        // 2-char values that fail ^[A-Z]{2}$ (not 3-char values, which would truncate on CHAR(2))
        assertRejected("INSERT INTO coupon (code, max_usage, country_code) VALUES ('CC1', 5, 'pl')",
                SQLSTATE_CHECK_VIOLATION); // lowercase
        assertRejected("INSERT INTO coupon (code, max_usage, country_code) VALUES ('CC2', 5, 'P1')",
                SQLSTATE_CHECK_VIOLATION); // contains a digit
    }

    @Test
    void couponUsageIncrementIsCappedByMaxUsage() throws SQLException {
        long couponId = insertCoupon("UPD1", 1);
        assertEquals(1, executeUpdateAsAppUser("UPDATE coupon SET current_usage = 1 WHERE id = " + couponId));
        assertRejected("UPDATE coupon SET current_usage = 2 WHERE id = " + couponId, SQLSTATE_CHECK_VIOLATION);
    }

    @Test
    void couponCodeIsUniqueCaseInsensitively() throws SQLException {
        insertCoupon("UNIQ1");
        assertRejected("INSERT INTO coupon (code, max_usage, country_code) VALUES ('uniq1', 5, 'PL')",
                SQLSTATE_UNIQUE_VIOLATION);
    }

    @Test
    void couponUsageIsUniquePerUser() throws SQLException {
        long couponId = insertCoupon("USE1");
        executeUpdateAsAppUser("INSERT INTO coupon_usage (coupon_id, user_id) VALUES (" + couponId + ", 7)");
        assertRejected("INSERT INTO coupon_usage (coupon_id, user_id) VALUES (" + couponId + ", 7)",
                SQLSTATE_UNIQUE_VIOLATION);
    }

    @Test
    void couponUsageRejectsUnknownCoupon() {
        assertRejected("INSERT INTO coupon_usage (coupon_id, user_id) VALUES (99999999, 7)",
                SQLSTATE_FOREIGN_KEY_VIOLATION);
    }
}