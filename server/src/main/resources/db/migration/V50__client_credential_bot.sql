-- A bot:decide key may be bound to one bot: it then decides for that bot only (docs/bots.md).
ALTER TABLE client_credential ADD COLUMN bot_id UUID;
