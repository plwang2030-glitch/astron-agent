-- A-share paper trading: trailing stop / partial take profit state per position, and the weekly
-- automatic strategy search switch.
ALTER TABLE paper_position
    ADD COLUMN peak_price DECIMAL(12,3) DEFAULT NULL COMMENT 'Highest price since the first buy (trailing stop)' AFTER cost_amount,
    ADD COLUMN partial_taken TINYINT NOT NULL DEFAULT 0 COMMENT '1: half already sold at the partial take profit' AFTER peak_price;
ALTER TABLE paper_account
    ADD COLUMN auto_evolve TINYINT NOT NULL DEFAULT 1 COMMENT '1: weekly automatic strategy search; monthly automatic rollback' AFTER monthly_review;
