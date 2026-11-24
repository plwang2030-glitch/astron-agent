-- 深度思考 switch per agent. 1 (default, the previous behaviour) leaves the model's own default;
-- 0 asks providers that support it (Volcengine Ark: thinking.type=disabled) to answer without reasoning.
ALTER TABLE chat_bot_base ADD COLUMN deep_thinking TINYINT NOT NULL DEFAULT 1 COMMENT '1 = model default (deep thinking), 0 = disable deep thinking' AFTER auto_team;
