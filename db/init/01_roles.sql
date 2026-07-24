CREATE ROLE coupon_db_owner LOGIN PASSWORD 'db_owner';

CREATE ROLE coupon_user LOGIN PASSWORD 'couponPass';

GRANT ALL ON SCHEMA PUBLIC TO coupon_db_owner;