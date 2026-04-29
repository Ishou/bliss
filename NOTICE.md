# Notices and attributions

This file enumerates third-party data sources whose licenses require
attribution when redistributed as part of Bliss. See ADR-0013 §1 for the
rationale.

## Word corpus — fr

- **Source:** `LibreOffice/dictionaries` `fr_FR` (Hunspell French
  dictionary).
- **License:** Mozilla Public License 2.0. The upstream `README_fr.txt`
  declares verbatim:

  > MPL : Mozilla Public License version 2.0 -- http://www.mozilla.org/MPL/2.0/

- **Canonical URL:**
  https://github.com/LibreOffice/dictionaries/tree/master/fr_FR

The exact upstream commit pinned at ingest time is recorded alongside
the importer's checksummed input in the `import-words` PR (PR2 of
ADR-0013). Until that PR lands, the `words` table is empty and no
Hunspell-fr data is redistributed by this repository.

## Word corpus — fr (Grammalecte)

- **Source:** `grammalecte.net` — `lexique-grammalecte-fr-v7.7.txt`
  (Grammalecte French grammar checker lexicon, v7.7).
- **License:** Mozilla Public License 2.0. Confirmed in the `LICENSE`
  file of the Grammalecte repository (`grammalecte.net`).
- **Canonical URL:** https://grammalecte.net

The version pinned at ingest time is `v7.7`, recorded in the `source`
column of the `words` table (`source = 'grammalecte-fr-v7.7'`) for
every row imported via `import-grammalecte`. See ADR-0014 for the
ingest rationale.
