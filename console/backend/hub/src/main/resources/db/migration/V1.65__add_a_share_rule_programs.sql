-- A library of the trading rules the system has invented, with what each one scored. The search can
-- then breed from what worked instead of starting from nothing, and diversity is kept by splitting
-- the library into islands that evolve separately (FunSearch / AlphaEvolve style).
CREATE TABLE IF NOT EXISTS `ashare_rule_program` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` varchar(64) NOT NULL,
  `island` int NOT NULL DEFAULT 0 COMMENT 'Sub-population, kept apart to preserve diversity',
  `kind` varchar(8) NOT NULL COMMENT 'ENTRY / EXIT',
  `expression` varchar(400) NOT NULL COMMENT 'RuleExpression source; nothing is executed',
  `expr_hash` char(32) NOT NULL COMMENT 'Hash of the normalized expression, to spot duplicates',
  `parent_id` bigint DEFAULT NULL COMMENT 'The program it was bred from',
  `generation` int NOT NULL DEFAULT 0,
  `idea` varchar(500) DEFAULT NULL COMMENT 'What the author was trying, in its own words',
  `oos_score` double DEFAULT NULL COMMENT 'Out-of-sample annualized Sharpe',
  `oos_return_pct` double DEFAULT NULL,
  `oos_max_drawdown_pct` double DEFAULT NULL,
  `trades` int DEFAULT NULL,
  `deflated_sharpe` double DEFAULT NULL,
  `passed_gate` tinyint NOT NULL DEFAULT 0,
  `status` varchar(16) NOT NULL DEFAULT 'SCORED' COMMENT 'SCORED / INVALID / UNTESTABLE',
  `note` varchar(500) DEFAULT NULL COMMENT 'Why it could not be scored, when it could not',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ashare_rule_hash` (`uid`,`kind`,`expr_hash`),
  KEY `idx_ashare_rule_island` (`uid`,`kind`,`island`,`oos_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Invented trading rules and their scores';
