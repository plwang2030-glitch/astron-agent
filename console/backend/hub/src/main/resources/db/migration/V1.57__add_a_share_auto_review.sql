-- A-share paper trading: daily automatic review and optional strategy auto-trading.
ALTER TABLE paper_account
    ADD COLUMN auto_review TINYINT NOT NULL DEFAULT 1 COMMENT '1: run the review agent after each trading day' AFTER frozen_cash,
    ADD COLUMN auto_trade TINYINT NOT NULL DEFAULT 0 COMMENT '1: the active strategy places entries and all exits itself; 0: only automatic stop loss' AFTER auto_review,
    ADD COLUMN review_bot_id INT DEFAULT NULL COMMENT 'Agent that runs the daily review; defaults to the first a_share agent the user chats with' AFTER auto_trade;
-- One row per review run (scheduled after the close, or started from the trading page).
CREATE TABLE IF NOT EXISTS `trading_review_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` varchar(128) NOT NULL,
  `trade_date` date NOT NULL,
  `trigger_type` varchar(16) NOT NULL COMMENT 'AUTO / MANUAL',
  `bot_id` int DEFAULT NULL,
  `status` varchar(16) NOT NULL COMMENT 'RUNNING / DONE / FAILED',
  `content` mediumtext COMMENT 'Review written by the agent',
  `error` varchar(500) DEFAULT NULL,
  `cost_ms` int DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trading_review_report_uid` (`uid`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A-share daily review reports';
