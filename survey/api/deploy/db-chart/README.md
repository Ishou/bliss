# survey-api db-chart

CNPG `Cluster` for the survey context. Deployed alongside the
`survey-api` chart (sibling release, this one first).

## UUIDv7 generation for campaign-lifecycle SQL

ADR-0003 §6 mandates UUIDv7 for wire identifiers. The runtime
survey-api stamps `campaign_id`, `rating_id`, and `pair_rating_id`
via the in-process Java UUIDv7 generator — no database extension
needed.

For the maintainer's psql-driven campaign-lifecycle SQL (ADR-0059),
the `pg_uuidv7` extension would be the cleanest path (one-line
`uuidv7()` in SQL), BUT the CNPG image
`ghcr.io/cloudnative-pg/postgresql:18.1-system-bookworm` does not
ship that extension and a `CREATE EXTENSION IF NOT EXISTS pg_uuidv7;`
fails with `extension is not available`. Adopting a custom Postgres
image to add it is out of scope; the documented workaround is to
generate the UUIDv7 client-side and pass it as a bind value:

```sh
# 1. Generate a UUIDv7 locally
CAMPAIGN_UUID=$(python3 -c 'import uuid_utils; print(uuid_utils.uuid7())')

# 2. Open a campaign (fails if one is already open per the partial unique index)
kubectl -n wordsparrow exec wordsparrow-survey-api-pg-1 -- \
  psql -U postgres -d survey -c \
  "INSERT INTO campaigns (campaign_id, batch_label) VALUES ('$CAMPAIGN_UUID'::uuid, 'round-7') RETURNING campaign_id, opened_at;"

# 3. Close the currently open campaign
kubectl -n wordsparrow exec wordsparrow-survey-api-pg-1 -- \
  psql -U postgres -d survey -c \
  "UPDATE campaigns SET closed_at = now() WHERE closed_at IS NULL RETURNING campaign_id, batch_label, closed_at;"
```

(`uuid_utils` is pip-installable: `pip install uuid-utils`.)

The first ever campaign must be opened before the sondage will accept
ratings (`POST /v1/items/{id}/rating` returns 423 when no open campaign
exists). After that point, the open/close verbs above drive each
training-batch window.
