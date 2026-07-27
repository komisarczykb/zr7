package me.bartoszkomisarczyk.zr7.integration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Least-privilege contract: the runtime user ({@code coupon_user}) may perform exactly the DML it
 * was granted and is denied destructive and DDL operations - the permission model Flyway's V3
 * grants establish.
 */
class DbUserPermissionsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void appUserCanInsertSelectAndUpdateCoupon() throws SQLException {
        long couponId = insertCoupon("ABCD12");

        try (Connection c = connectAsAppUser(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT current_usage, max_usage FROM coupon WHERE id = " + couponId)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("current_usage"));
                assertEquals(5, rs.getInt("max_usage"));
            }
            assertEquals(1, s.executeUpdate("UPDATE coupon SET current_usage = 1 WHERE id = " + couponId));
        }
    }

    @Test
    void appUserCanInsertAndSelectCouponUsage() throws SQLException {
        long couponId = insertCoupon("USAGE1");

        try (Connection c = connectAsAppUser(); Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate(
                    "INSERT INTO coupon_usage (coupon_id, user_id) VALUES (" + couponId + ", 42)"));
            try (ResultSet rs = s.executeQuery("SELECT user_id FROM coupon_usage WHERE coupon_id = " + couponId)) {
                assertTrue(rs.next());
                assertEquals(42, rs.getLong("user_id"));
            }
        }
    }

    @Test
    void appUserCannotDeleteFromCoupon() {
        assertDenied("DELETE FROM coupon");
    }

    @Test
    void appUserCannotModifyOrDeleteCouponUsage() {
        assertDenied("UPDATE coupon_usage SET user_id = user_id + 1");
        assertDenied("DELETE FROM coupon_usage");
    }

    @Test
    void appUserCannotPerformDdl() {
        assertDenied("TRUNCATE coupon");
        assertDenied("ALTER TABLE coupon ADD COLUMN redundant INT");
        assertDenied("DROP TABLE coupon");
        assertDenied("CREATE TABLE should_not_exist (id INT)");
    }
}