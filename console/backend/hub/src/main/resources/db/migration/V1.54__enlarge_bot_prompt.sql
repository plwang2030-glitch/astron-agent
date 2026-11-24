-- Allow agent prompts (个性化设置) up to 6000 characters. The prompt is copied from chat_bot_base into
-- chat_bot_market on publish and into chat_bot_list on create, and chat_bot_remove keeps the same shape,
-- so all four columns are widened together to avoid truncation errors further down the chain.
ALTER TABLE chat_bot_base MODIFY COLUMN prompt VARCHAR(6000) DEFAULT NULL COMMENT 'bot_prompt';
ALTER TABLE chat_bot_market MODIFY COLUMN prompt VARCHAR(6000) DEFAULT NULL COMMENT 'bot_prompt';
ALTER TABLE chat_bot_list MODIFY COLUMN prompt VARCHAR(6000) DEFAULT NULL COMMENT 'bot_prompt';
ALTER TABLE chat_bot_remove MODIFY COLUMN prompt VARCHAR(6000) DEFAULT NULL COMMENT 'bot_prompt';
