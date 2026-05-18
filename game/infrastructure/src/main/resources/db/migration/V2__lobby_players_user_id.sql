-- Phase 6c: lobby seats carry the authenticated user_id when the player
-- signed in. NULL for anon seats. Rebind/unbind endpoints flip this
-- column atomically with the pseudonym refresh, matching the in-memory
-- adapter's per-player atomic update.
ALTER TABLE lobby_players ADD COLUMN user_id UUID NULL;
CREATE INDEX idx_lobby_players_user_id ON lobby_players(user_id) WHERE user_id IS NOT NULL;
