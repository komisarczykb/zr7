package me.bartoszkomisarczyk.zr7.adapter.coupon;

import me.bartoszkomisarczyk.zr7.domain.coupon.Coupon;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponLookup;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
@Qualifier("delegateCouponRepository")
public class JdbcCouponRepository implements CouponRepository {

    private static final String LOOKUP_BY_CODE = """
            SELECT id, country_code FROM coupon WHERE UPPER(code) = UPPER(:code)
            """;

    private static final String FULL_LOOKUP_BY_CODE = """
            SELECT id, code, creation_date, max_usage, current_usage, country_code
            FROM coupon WHERE UPPER(code) = UPPER(:code)
            """;

    private static final String INSERT_COUPON = """
            INSERT INTO coupon (code, max_usage, country_code)
            VALUES (:code, :maxUsage, :countryCode)
            RETURNING id, code, creation_date, max_usage, current_usage, country_code
            """;

    private static final String INSERT_USAGE = """
            INSERT INTO coupon_usage (coupon_id, user_id)
            VALUES (:couponId, :userId)
            ON CONFLICT (coupon_id, user_id) DO NOTHING
            """;

    private static final String CONDITIONALLY_INCREMENT_USAGE = """
            UPDATE coupon
            SET current_usage = current_usage + 1
            WHERE id = :couponId AND current_usage < max_usage
            """;

    private final JdbcClient jdbcClient;

    public JdbcCouponRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<CouponLookup> findLookupByCode(String code) {
        return jdbcClient.sql(LOOKUP_BY_CODE)
                .param("code", code)
                .query((rs, rowNum) -> new CouponLookup(rs.getLong("id"), rs.getString("country_code")))
                .optional();
    }

    @Override
    public Optional<Coupon> findByCode(String code) {
        return jdbcClient.sql(FULL_LOOKUP_BY_CODE)
                .param("code", code)
                .query(JdbcCouponRepository::mapCoupon)
                .optional();
    }

    @Override
    public Coupon insert(String code, int maxUsage, String countryCode) {
        return jdbcClient.sql(INSERT_COUPON)
                .param("code", code)
                .param("maxUsage", maxUsage)
                .param("countryCode", countryCode)
                .query(JdbcCouponRepository::mapCoupon)
                .single();
    }

    @Override
    public int insertUsage(long couponId, long userId) {
        return jdbcClient.sql(INSERT_USAGE)
                .param("couponId", couponId)
                .param("userId", userId)
                .update();
    }

    @Override
    public int incrementUsage(long couponId) {
        return jdbcClient.sql(CONDITIONALLY_INCREMENT_USAGE)
                .param("couponId", couponId)
                .update();
    }

    private static Coupon mapCoupon(ResultSet rs, int rowNum) throws SQLException {
        Timestamp creationDate = rs.getTimestamp("creation_date");
        return new Coupon(
                rs.getLong("id"),
                rs.getString("code"),
                creationDate.toInstant(),
                rs.getInt("max_usage"),
                rs.getInt("current_usage"),
                rs.getString("country_code"));
    }
}