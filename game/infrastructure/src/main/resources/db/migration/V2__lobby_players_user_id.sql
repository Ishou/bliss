-- Phase 6c: nullable user_id for authed seats; rebind/unbind flip it atomically.
ALTER TABLE lobby_players ADD COLUMN user_id UUID NULL;
CREATE INDEX idx_lobby_players_user_id ON lobby_players(user_id) WHERE user_id IS NOT NULL;
