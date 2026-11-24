-- Sub-agents attached to an agent: [{"botId":1,"name":"...","description":"...","avatar":"..."}].
-- At chat time each one is exposed to the model as a call_agent_<botId> tool and the model routes by intent.
ALTER TABLE chat_bot_base
    ADD COLUMN agents text NULL COMMENT 'Attached sub-agents JSON' AFTER workflows;
