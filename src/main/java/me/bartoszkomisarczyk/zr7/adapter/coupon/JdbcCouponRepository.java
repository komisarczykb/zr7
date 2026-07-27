package me.bartoszkomisarczyk.zr7.adapter.coupon;

import me.bartoszkomisarczyk.zr7.domain.coupon.CouponLookup;
import me.bartoszkomisarczyk.zr7.domain.coupon.CouponRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcCouponRepository implements CouponRepository {

    private static final String LOOKUP_BY_CODE = """
            SELECT id, country_code FROM coupon WHERE UPPER(code) = UPPER(:code)
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
    public Optional<CouponLookup> findByCode(String code) {
        return jdbcClient.sql(LOOKUP_BY_CODE)
                .param("code", code)
                .query((rs, rowNum) -> new CouponLookup(rs.getInt("id"), rs.getString("country_code")))
                .optional();
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
}