CREATE TABLE coupon (
    id SERIAL PRIMARY KEY,
    code VARCHAR(16) NOT NULL,
    creation_date DATE NOT NULL DEFAULT CURRENT_DATE,
    max_usage INT NOT NULL CHECK (max_usage > 0),
    current_usage INT NOT NULL DEFAULT 0 CHECK (current_usage >= 0),
    country_code CHAR(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT check_usage_not_exceeded CHECK (current_usage <= max_usage)
);

CREATE UNIQUE INDEX unique_coupon_code_upper ON coupon (UPPER(code));