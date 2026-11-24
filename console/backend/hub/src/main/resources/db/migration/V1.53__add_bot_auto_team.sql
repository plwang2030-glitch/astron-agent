-- Expert team: let the agent pick experts from the space's published agents per question.
ALTER TABLE chat_bot_base ADD COLUMN auto_team TINYINT NOT NULL DEFAULT 0 COMMENT '1 = pick experts from the expert library per question' AFTER agents;
