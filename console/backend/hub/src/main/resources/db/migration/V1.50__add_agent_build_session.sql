CREATE TABLE IF NOT EXISTS `agent_build_session` (
  `id` varchar(64) NOT NULL COMMENT 'Build session ID',
  `uid` varchar(128) NOT NULL COMMENT 'Owner user ID',
  `space_id` bigint DEFAULT NULL COMMENT 'Space ID, NULL for personal space',
  `title` varchar(255) DEFAULT NULL COMMENT 'Session title (initial goal summary)',
  `draft_json` longtext COMMENT 'Current agent draft configuration JSON',
  `draft_history` longtext COMMENT 'Undo snapshots of previous drafts (JSON array, newest last)',
  `messages_json` longtext COMMENT 'Builder conversation messages JSON array',
  `status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT 'draft / published',
  `bot_id` int DEFAULT NULL COMMENT 'Bot created from this draft',
  `is_delete` tinyint NOT NULL DEFAULT 0 COMMENT 'Deletion status: 0 not deleted, 1 deleted',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`),
  KEY `idx_agent_build_session_uid` (`uid`, `space_id`, `is_delete`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent builder draft sessions';
