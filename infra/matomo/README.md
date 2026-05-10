# Matomo + MariaDB chart

Self-hosted [Matomo](https://matomo.org/) + a private MariaDB for Bliss
product analytics, configured for the CNIL audience-measurement
consent exemption per **ADR-0025**.

## Why no Bitnami / no third-party chart

Matomo has no first-party Helm chart. The de facto choice is the
Bitnami chart, but Bitnami restructured its public free-tier support
in 2024-2025 and the long-term shape of community charts is uncertain.
To keep the analytics stack auditable and aligned with ADR-0025's
"zero new sub-processors" stance, this chart is a thin first-party
wrapper around the official `matomo` and `mariadb` images. The
template surface is small enough to read in one sitting.

## What gets deployed

- A `Deployment` running `matomo:5.x-apache` (single container; Apache
  + mod_php in one image).
- A `StatefulSet` running `mariadb:11.x` with a per-instance PVC.
- A headless `Service` for MariaDB and a `ClusterIP` `Service` for
  Matomo.
- An `Ingress` for `analytics.wordsparrow.io` reusing the cluster's
  `ingress-nginx` + `cert-manager` + `external-dns` operators
  (`infra/platform/`).
- A `ConfigMap` mounting `common.config.ini.php` with the
  CNIL-exempt settings (see ADR-0025 §2).

The CNIL settings — IP anonymization at 2 bytes, DoNotTrack honoured,
country-only geolocation, no fingerprinting plugins, cookieless — are
load-bearing. **If any of them is loosened, the consent exemption is
lost and a banner becomes legally required.** Changes go through Git
and a follow-up ADR.

## Bootstrap

One-time secret creation (run from a host with `kubectl` configured
against the prod cluster):

```sh
kubectl create namespace matomo

# Random passwords; keep them in your password manager.
MARIADB_ROOT_PW=$(openssl rand -hex 24)
MATOMO_DB_PW=$(openssl rand -hex 24)

kubectl -n matomo create secret generic mariadb-env \
  --from-literal=MARIADB_ROOT_PASSWORD="$MARIADB_ROOT_PW" \
  --from-literal=MARIADB_USER=matomo \
  --from-literal=MARIADB_PASSWORD="$MATOMO_DB_PW" \
  --from-literal=MARIADB_DATABASE=matomo

kubectl -n matomo create secret generic matomo-env \
  --from-literal=MATOMO_DATABASE_USERNAME=matomo \
  --from-literal=MATOMO_DATABASE_PASSWORD="$MATOMO_DB_PW" \
  --from-literal=MATOMO_DATABASE_DBNAME=matomo
```

The admin dashboard sits behind `nginx.ingress.kubernetes.io/auth-type:
basic` referencing an `admin-htpasswd` Secret per **ADR-0032**.
Tracker beacons (`/matomo.php`, `/matomo.js`, `/piwik.*`, `/js/*`) are
explicitly carved out so embedded `<script>` tags on the public site
keep working without auth. Bootstrap the Secret in this namespace via
the same script that handles the observability namespace:

```sh
./infra/observability/scripts/bootstrap-admin-htpasswd.sh \
  --namespace=matomo
```

Secrets are namespace-scoped, so `observability/admin-htpasswd` and
`matomo/admin-htpasswd` are two distinct Secrets with two passwords by
default. Use the same password for both by recording the first one
from the script's stdout and applying it to the second namespace by
hand if you'd rather have a single 1Password entry.

Install:

```sh
helm install matomo infra/matomo/ \
  --namespace matomo \
  -f infra/matomo/values-prod.yaml \
  --set matomo.image.digest="sha256:..." \
  --set mariadb.image.digest="sha256:..."
```

Image digests resolve via `docker buildx imagetools inspect
matomo:5.2.1-apache --format '{{.Manifest.Digest}}'` (and same for
`mariadb:11.4.4-noble`). The `requireDigest: true` guard in
`values-prod.yaml` aborts `helm install/upgrade` if either is empty.

## Post-install Matomo wizard

On first visit to `https://analytics.wordsparrow.io`, Matomo runs a
five-step setup wizard:

1. System check — should pass; if it complains about file permissions
   the PVC `fsGroup` (33 in `values.yaml`) is the culprit.
2. Database setup — point at `matomo-mariadb` with the credentials
   from the bootstrap secret.
3. Super-user creation — pick an admin email + password. Stored in
   the database.
4. Site setup — name `Bliss`, URL `https://wordsparrow.io`, time zone
   `Europe/Paris`.
5. JavaScript code — ignore. The frontend tracker (`Phase 3`) loads
   Matomo via its npm-bundled snippet, not by copy-pasting from this
   wizard.

After the wizard, walk the **Privacy** panel under Settings:

- Anonymize visitor IP → check, mask 2 bytes (matches ConfigMap).
- Anonymize previous logs → check; set retention to 13 months (CNIL
  guidance, matches ConfigMap `delete_logs_older_than = 395`).
- Visitor opt-out → enable; embed link in the `/confidentialite` page
  (Phase 5).
- DoNotTrack support → check (matches ConfigMap).
- "Anonymous Tracking" / `disableCookies` → confirm enabled.

Run **GDPR Manager → Data subject rights tools** once to verify the
delete-visits-by-visitor-ID workflow; this is what Phase 6's erasure
endpoint will call.

## Backup

This chart **does not** integrate with the Hetzner Storage Box backup
flow used by CNPG (ADR-0010). Add a separate backup CronJob that
`mariadb-dump` to the Storage Box on a schedule before relying on
Matomo data. Tracked in the implementation plan under follow-up work.

## Disaster recovery

A clean reinstall:

1. Re-run the bootstrap with the same secret values.
2. Restore the MariaDB data PVC from backup (when implemented).
3. Restore the Matomo data PVC from backup (mostly stateless beyond
   `config.ini.php`; can be regenerated by re-running the wizard).
4. Cookie-less tracking has no client-side state, so visitors are
   unaffected.

## Limits and follow-up

- Single replica; horizontal scaling needs ReadWriteMany or external
  session storage.
- No backup integration yet (above).
- The Matomo image is pulled from Docker Hub directly; if rate
  limits become a concern, mirror to GHCR.
