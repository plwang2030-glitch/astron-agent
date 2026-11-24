-- Rule-based A-share stock universe: monthly snapshots of the screen (trading universe and wider
-- validation pool), replacing the hand-picked lists.
CREATE TABLE IF NOT EXISTS `ashare_universe_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `month` varchar(7) NOT NULL COMMENT 'Screen month, e.g. 2026-11',
  `as_of` date NOT NULL COMMENT 'Last trading day whose data the screen used',
  `rules` varchar(1000) NOT NULL COMMENT 'Screening rules JSON',
  `trade_codes` text NOT NULL COMMENT 'Ranked trading universe codes JSON',
  `pool_codes` text NOT NULL COMMENT 'Ranked validation pool codes JSON',
  `details` mediumtext COMMENT 'Per-stock metrics of the pool JSON',
  `candidates` int NOT NULL DEFAULT 0 COMMENT 'Stocks screened',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ashare_universe_month` (`month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Monthly A-share universe screens';
-- Series downloaded through the Tencent fallback had no turnover amount, which the screen needs:
-- delete those series whole (not single rows, which would leave gaps) so they are downloaded again.
DELETE b FROM ashare_daily_bar b
JOIN (SELECT secid, adjusted FROM ashare_daily_bar GROUP BY secid, adjusted HAVING SUM(amount = 0) > 0) z
  ON b.secid = z.secid AND b.adjusted = z.adjusted;
