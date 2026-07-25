GRANT USAGE ON SCHEMA public to "coupon_user";
-- minimal required permissions
GRANT SELECT, INSERT, UPDATE ON coupon to "coupon_user";
GRANT SELECT, INSERT ON coupon_usage TO "coupon_user";

GRANT USAGE, SELECT ON SEQUENCE coupon_id_seq TO "coupon_user";
GRANT USAGE, SELECT ON SEQUENCE coupon_usage_id_seq TO "coupon_user";