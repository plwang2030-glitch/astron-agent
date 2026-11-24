-- A-share analysis and paper trading with recursive self-improvement.

-- Opt-in built-in tool groups per agent, e.g. ["a_share"]. Not sent by the config page, so UI saves keep it.
ALTER TABLE chat_bot_base ADD COLUMN builtin_tools VARCHAR(255) DEFAULT NULL
    COMMENT 'JSON array of opt-in built-in tool groups, e.g. ["a_share"]' AFTER deep_thinking;

-- One simulated account per user.
CREATE TABLE IF NOT EXISTS `paper_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` varchar(128) NOT NULL,
  `initial_cash` decimal(18,2) NOT NULL,
  `cash` decimal(18,2) NOT NULL COMMENT 'Available cash',
  `frozen_cash` decimal(18,2) NOT NULL DEFAULT 0 COMMENT 'Reserved by pending buy orders',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_account_uid` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A-share paper trading account';

CREATE TABLE IF NOT EXISTS `paper_position` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `code` varchar(16) NOT NULL,
  `name` varchar(64) DEFAULT NULL,
  `qty` int NOT NULL,
  `cost_amount` decimal(18,2) NOT NULL COMMENT 'Total cost including fees',
  `first_buy_date` date DEFAULT NULL,
  `last_buy_date` date DEFAULT NULL COMMENT 'Shares bought on this date cannot be sold the same day (T+1)',
  `last_buy_qty` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_position` (`account_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A-share paper trading position';

-- Orders fill at the open of the next trading session after they are placed.
CREATE TABLE IF NOT EXISTS `paper_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `code` varchar(16) NOT NULL,
  `name` varchar(64) DEFAULT NULL,
  `side` varchar(8) NOT NULL COMMENT 'BUY / SELL',
  `qty` int NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'PENDING / FILLED / REJECTED / CANCELLED',
  `source` varchar(16) NOT NULL DEFAULT 'AGENT' COMMENT 'AGENT / STRATEGY (automatic exit)',
  `placed_at` datetime NOT NULL,
  `frozen_amount` decimal(18,2) NOT NULL DEFAULT 0,
  `fill_date` date DEFAULT NULL,
  `fill_price` decimal(12,3) DEFAULT NULL,
  `fill_amount` decimal(18,2) DEFAULT NULL,
  `fees` decimal(12,2) DEFAULT NULL,
  `realized_pnl` decimal(18,2) DEFAULT NULL COMMENT 'Sell orders: proceeds minus cost of the shares sold',
  `reason` text COMMENT 'Why the order was placed',
  `expectation` varchar(1000) DEFAULT NULL COMMENT 'Expected move and horizon, checked in review',
  `confidence` int DEFAULT NULL COMMENT '1-5',
  `strategy_version` int DEFAULT NULL,
  `reject_reason` varchar(255) DEFAULT NULL,
  `review_status` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE / REVIEWED',
  `review_outcome` varchar(16) DEFAULT NULL COMMENT 'CORRECT / WRONG / MIXED',
  `review_note` text,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_paper_order_account_status` (`account_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A-share paper trading order';

CREATE TABLE IF NOT EXISTS `paper_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `trade_date` date NOT NULL,
  `cash` decimal(18,2) NOT NULL,
  `market_value` decimal(18,2) NOT NULL,
  `total` decimal(18,2) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_snapshot` (`account_id`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily account value';

-- Lessons learned from reviews (LESSON / PREFERENCE) and from strategy evolution attempts (META).
CREATE TABLE IF NOT EXISTS `trading_lesson` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` varchar(128) NOT NULL,
  `kind` varchar(16) NOT NULL COMMENT 'LESSON / PREFERENCE / META',
  `content` varchar(1000) NOT NULL,
  `tags` varchar(255) DEFAULT NULL,
  `evidence` varchar(1000) DEFAULT NULL COMMENT 'JSON array of order ids or strategy versions',
  `support` int NOT NULL DEFAULT 0,
  `contradict` int NOT NULL DEFAULT 0,
  `confidence` decimal(4,2) NOT NULL DEFAULT 0.50,
  `status` varchar(16) NOT NULL COMMENT 'CANDIDATE / ACTIVE / RETIRED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trading_lesson_uid` (`uid`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Trading lessons';

-- Versioned, backtestable strategy parameters. A candidate becomes ACTIVE only when it beats the
-- active version out of sample.
CREATE TABLE IF NOT EXISTS `trading_strategy_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` varchar(128) NOT NULL,
  `version` int NOT NULL,
  `parent_version` int DEFAULT NULL,
  `params` text NOT NULL COMMENT 'Strategy parameters JSON',
  `rationale` text COMMENT 'Why this change was proposed',
  `status` varchar(16) NOT NULL COMMENT 'ACTIVE / RETIRED / REJECTED',
  `evaluation` text COMMENT 'Backtest result of candidate and baseline JSON',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trading_strategy_version` (`uid`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Trading strategy versions';
