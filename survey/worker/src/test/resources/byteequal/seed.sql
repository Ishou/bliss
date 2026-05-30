-- Byte-equal export fixture. Two items with deterministic UUIDs + ratings.
-- The export sorts by item_id (UUID natural order), so the row order is
-- item-id-0001 (PAIN) then item-id-0002 (POULE).

INSERT INTO survey_items
  (item_id, mot, definition, pos, categorie, style, force_claimed, longueur,
   source, source_batch, tier, is_calibration, expected, retired_at, created_at)
VALUES
  ('00000000-0000-0000-0000-000000000001',
   'PAIN', 'Aliment de boulangerie',
   'nom_commun', 'aliments', 'definition_directe',
   1, 4, 'gold', 'gold_v1', 'mid', FALSE, NULL, NULL,
   '2026-05-25T12:00:00Z'),
  ('00000000-0000-0000-0000-000000000002',
   'POULE', 'Femelle du coq',
   'nom_commun', 'animals', 'periphrase',
   2, 5, 'gold', 'gold_v1', 'mid', FALSE, NULL, NULL,
   '2026-05-25T12:00:00Z');

-- Ratings belong to a closed campaign past the 8 s grace so they settle (ADR-0059).
INSERT INTO campaigns
  (campaign_id, batch_label, opened_at, closed_at)
VALUES
  ('00000000-0000-0000-0000-0000000000c1',
   'gold_v1',
   '2026-05-25T11:00:00Z',
   '2026-05-25T12:00:00Z')
ON CONFLICT (campaign_id) DO NOTHING;

-- Item 1: one auth rating (qualite=4, difficulte=2), one anon (qualite=3, difficulte=2).
INSERT INTO ratings
  (rating_id, item_id, user_id, submitted_as, qualite, difficulte,
   flag, proposed_item_id, latency_ms, client_meta, created_at, campaign_id)
VALUES
  ('00000000-0000-0000-0000-000000000101',
   '00000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-0000000000a1',
   'auth', 4, 2, NULL, NULL, NULL, NULL,
   '2026-05-25T12:00:00Z',
   '00000000-0000-0000-0000-0000000000c1'),
  ('00000000-0000-0000-0000-000000000102',
   '00000000-0000-0000-0000-000000000001',
   NULL,
   'anon', 3, 2, NULL, NULL, NULL, NULL,
   '2026-05-25T12:00:00Z',
   '00000000-0000-0000-0000-0000000000c1');

-- Item 2: two distinct auth raters (qualite=5/4, difficulte=3/4).
INSERT INTO ratings
  (rating_id, item_id, user_id, submitted_as, qualite, difficulte,
   flag, proposed_item_id, latency_ms, client_meta, created_at, campaign_id)
VALUES
  ('00000000-0000-0000-0000-000000000201',
   '00000000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-0000000000a1',
   'auth', 5, 3, NULL, NULL, NULL, NULL,
   '2026-05-25T12:00:00Z',
   '00000000-0000-0000-0000-0000000000c1'),
  ('00000000-0000-0000-0000-000000000202',
   '00000000-0000-0000-0000-000000000002',
   '00000000-0000-0000-0000-0000000000a2',
   'auth', 4, 4, NULL, NULL, NULL, NULL,
   '2026-05-25T12:00:00Z',
   '00000000-0000-0000-0000-0000000000c1');
