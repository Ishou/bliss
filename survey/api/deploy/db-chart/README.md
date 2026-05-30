# survey-api db-chart

CNPG `Cluster` for the survey context. Deployed alongside the
`survey-api` chart (sibling release, this one first).

## Extensions

The cluster bootstraps with `pg_uuidv7` installed via
`bootstrap.initdb.postInitSQL` so the maintainer's campaign-lifecycle
SQL backdoor (ADR-0059) can call `uuidv7()` directly. `postInitSQL`
runs as superuser at cluster-create time only — for an already-running
CNPG cluster, install the extension once by hand:

```sh
kubectl cnpg psql <release-name> -- -d survey -c \
  "CREATE EXTENSION IF NOT EXISTS pg_uuidv7;"
```

(or `kubectl exec -it <release-name>-1 -- psql -U postgres -d survey -c '...'`
if the cnpg plugin is not installed locally).

Verify with `SELECT uuidv7();` from a `survey`-database session.

## Campaign lifecycle verbs (ADR-0059)

Once `pg_uuidv7` is in place, the maintainer drives campaigns via:

```sql
-- open a new campaign (fails if one is already open per partial unique index)
INSERT INTO campaigns (campaign_id, batch_label)
     VALUES (uuidv7(), 'round-7')
  RETURNING campaign_id, opened_at;

-- close the currently open campaign
UPDATE campaigns SET closed_at = now()
 WHERE closed_at IS NULL
RETURNING campaign_id, batch_label, closed_at;
```

The first ever campaign must be opened before the sondage will accept
ratings (`POST /v1/items/{id}/rating` returns 423 when no campaign is
open).
