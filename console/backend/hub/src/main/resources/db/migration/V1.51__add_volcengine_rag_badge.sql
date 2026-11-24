-- Badge shown on knowledge base cards for the Volcengine (VikingDB) knowledge type.
-- name + value form the badge URL (see RepoService#buildRagIconMap); the SVG ships with the frontend.
INSERT INTO `config_info` (`category`, `code`, `name`, `value`, `is_valid`, `remarks`, `create_time`, `update_time`)
SELECT 'ICON', 'rag', '', '/rag-badges/volcengine.svg', 1, 'Volcengine-RAG', NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `config_info` WHERE `category` = 'ICON' AND `code` = 'rag' AND `remarks` = 'Volcengine-RAG'
);
