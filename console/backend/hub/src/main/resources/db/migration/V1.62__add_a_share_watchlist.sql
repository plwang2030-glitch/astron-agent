-- A-share paper trading: the user's watchlist. Watched stocks are scanned with the active strategy at
-- 14:50 like the rule-based universe; with watchlist_auto_trade on (and auto-trading on) a signal is
-- bought automatically, otherwise the user is only notified.
ALTER TABLE paper_account
    ADD COLUMN watchlist VARCHAR(1000) DEFAULT NULL COMMENT 'Watched stock codes, JSON array' AFTER auto_trade,
    ADD COLUMN watchlist_auto_trade TINYINT NOT NULL DEFAULT 1 COMMENT '1: buy watchlist signals automatically; 0: notify only' AFTER watchlist;
