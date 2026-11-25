-- Every candidate ever measured against the out-of-sample data. Without this record the number of
-- trials is unknown, and a Sharpe ratio picked as the best of many tries cannot be deflated: the
-- search would keep finding luck and calling it skill.
CREATE TABLE IF NOT EXISTS `ashare_search_trial` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `uid` varchar(64) NOT NULL,
  `params_hash` char(32) NOT NULL COMMENT 'Hash of the candidate params, to spot repeats',
  `changes` varchar(500) DEFAULT NULL COMMENT 'What it changed from its parent, for reading',
  `fields` varchar(255) DEFAULT NULL COMMENT 'Comma-separated changed field names, for per-field stats',
  `base_version` int DEFAULT NULL,
  `oos_score` double DEFAULT NULL COMMENT 'Out-of-sample annualized Sharpe',
  `oos_return_pct` double DEFAULT NULL,
  `significance_z` double DEFAULT NULL,
  `deflated_sharpe` double DEFAULT NULL COMMENT 'Probability the true Sharpe is above zero',
  `trials_at_time` int DEFAULT NULL COMMENT 'Cumulative trial count used for the deflation',
  `passed` tinyint NOT NULL DEFAULT 0,
  `origin` varchar(16) NOT NULL DEFAULT 'SEARCH' COMMENT 'SEARCH / PROPOSAL',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ashare_trial_uid` (`uid`,`create_time`),
  KEY `idx_ashare_trial_hash` (`uid`,`params_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Candidates measured during strategy search';
