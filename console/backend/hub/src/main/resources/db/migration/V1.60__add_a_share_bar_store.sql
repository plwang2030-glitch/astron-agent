-- Local store of daily bars for backtests, so that evaluations do not download years of history from
-- the unofficial market data endpoints each time (and keep working while they are unavailable).
CREATE TABLE IF NOT EXISTS `ashare_daily_bar` (
  `secid` varchar(16) NOT NULL COMMENT 'Eastmoney security id, e.g. 1.600036',
  `adjusted` tinyint NOT NULL COMMENT '1 forward-adjusted, 0 actual prices',
  `trade_date` date NOT NULL,
  `name` varchar(64) DEFAULT NULL,
  `open` double NOT NULL,
  `close` double NOT NULL,
  `high` double NOT NULL,
  `low` double NOT NULL,
  `volume` double NOT NULL,
  `amount` double NOT NULL DEFAULT 0,
  `pct_chg` double NOT NULL DEFAULT 0,
  `turnover_pct` double NOT NULL DEFAULT 0,
  PRIMARY KEY (`secid`, `adjusted`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily bars cached for A-share backtests';
