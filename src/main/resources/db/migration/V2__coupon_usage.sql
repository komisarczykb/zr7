CREATE TABLE coupon_usage (
    id BIGSERIAL PRIMARY KEY,
    coupon_id INT NOT NULL REFERENCES coupon(id),
    user_id BIGINT NOT NULL, /*Assumption that each request comes with some id*/
    used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT unique_single_use UNIQUE (coupon_id, user_id)
);