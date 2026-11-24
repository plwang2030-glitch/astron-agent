-- A-share paper trading: intraday fills (market / limit orders), intraday auto-trading and scheduled
-- pre-open / weekly / monthly reviews.
ALTER TABLE paper_order
    ADD COLUMN order_type VARCHAR(8) NOT NULL DEFAULT 'MARKET' COMMENT 'MARKET / LIMIT' AFTER side,
    ADD COLUMN limit_price DECIMAL(12,2) DEFAULT NULL COMMENT 'LIMIT orders: worst acceptable price' AFTER order_type,
    ADD COLUMN fill_time DATETIME DEFAULT NULL COMMENT 'Time of the fill (open auction or intraday)' AFTER fill_date;
-- Auto-trading is on by default (intraday strategy entries and exits); existing accounts follow the new default.
ALTER TABLE paper_account
    ALTER COLUMN auto_trade SET DEFAULT 1,
    ADD COLUMN pre_open_review TINYINT NOT NULL DEFAULT 1 COMMENT '1: plan before each open' AFTER auto_review,
    ADD COLUMN weekly_review TINYINT NOT NULL DEFAULT 1 COMMENT '1: review after the last trading day of each week' AFTER pre_open_review,
    ADD COLUMN monthly_review TINYINT NOT NULL DEFAULT 1 COMMENT '1: review after the last trading day of each month' AFTER weekly_review;
UPDATE paper_account SET auto_trade = 1;
ALTER TABLE trading_review_report
    ADD COLUMN review_type VARCHAR(16) NOT NULL DEFAULT 'CLOSE' COMMENT 'PRE_OPEN / CLOSE / WEEKLY / MONTHLY' AFTER trigger_type,
    ADD COLUMN period VARCHAR(16) DEFAULT NULL COMMENT 'Reviewed period: 2026-11-23 / 2026-W48 / 2026-11' AFTER review_type,
    ADD KEY idx_trading_review_report_period (uid, review_type, period);
