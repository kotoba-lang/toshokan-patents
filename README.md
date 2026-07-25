# toshokan-patents

Self-growing **worldwide patent bibliographic** harvest — a sibling of
`kotoba-lang/toshokan` that applies the same self-growing resident-loop pattern
([ADR-2607255100](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607255100-toshokan-self-growing-resident-ingest.edn))
to patent data instead of national-library catalogs. Design:
[ADR-2607251552](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607251552-toshokan-patents-worldwide-patent-resident-harvest.edn).

This repo's own git history is the source of truth (ADR-2607072300); the
kotobase.net graph is a derived, rebuildable index over it.

## What it does

Fetches patent bibliographic metadata — title, applicant, inventor, number,
country, filing/grant dates, cited patents — from public sources and stores it
as git-authoritative EDN quads `[entity attr value tx op]` for DataScript /
Datomic query. **The repo grows itself by walking the citation graph**: each
harvested patent's cited patents (`DC.relation scheme=references`) become new
seeds for the next tick.

**Metadata-only.** No claims / specification full text. No bot-detection
bypass. Identifying User-Agent, sequential requests, polite rate.

## Self-growing resident loop

| file | role |
|---|---|
| `seeds.edn` | patent-id seed list (hand-editable; daemon also appends citation-grown seeds) |
| `scripts/daemon.cljs` | one tick: lookup → dedupe → journal append → citation-graph seed grow → optional git push + kotobase ingest |
| `state.edn` | cursor / exhausted seeds (daemon-managed) |
| `scripts/harvest.cljs` | one-shot lookup by patent id |
| `scripts/query.cljs` | local DataScript query over journals |
| `deploy/com.kotoba-lang.toshokan-patents-tick.plist` | macOS LaunchAgent for residency (6h) |

```bash
# one tick (lookup only)
nbb --classpath src scripts/daemon.cljs --once

# one tick + git push + kotobase.net fold (what the LaunchAgent runs)
nbb --classpath src scripts/daemon.cljs --once --push --ingest

# one-shot lookup by patent id
npx nbb --classpath "src" scripts/harvest.cljs US8697359B1

# local query surface
nbb --classpath src scripts/query.cljs stats
nbb --classpath src scripts/query.cljs sample 10
nbb --classpath src scripts/query.cljs q \
  '[:find ?n ?a :where [?e "patent/number" ?n] [?e "patent/applicant" ?a]]'
```

Residency on the murakumo fleet host is a **LaunchAgent**
(`com.kotoba-lang.toshokan-patents-tick`, 6h, same class as `toshokan-tick` /
`fleet-ci-murakumo-tick` / `itonami-qwen36-tick`), not a WASM `on-tick` guest
yet — that waits on ADR-2607252400 CID capabilities (non-exclusive).

## Source: Google Patents (v1; USPTO ODP / EPO OPS later)

`patents.google.com/patent/<id>/en` — public, server-rendered HTML with rich
Dublin Core (`DC.date`, `DC.contributor`), `citation_patent_number`, and
`DC.relation scheme=references` (cited patents) metadata. It covers the
WORLDWIDE bibliographic space (US / EP / JP / WO / CN / KR / …) in one source —
effectively a free, no-auth mirror of the EPO DOCDB bibliographic set — and the
citation edges let the daemon self-grow by walking the citation graph.

USPTO Open Data Portal API is mid-migration (`search.patentsview.org` sunset
2026-03-20; `data.uspto.gov` API path still fluid). EPO OPS needs OAuth
registration. Both are non-exclusive future sources — the journal schema and
daemon are source-agnostic.

## Schema

Quads use the `[entity attr value tx op]` shape (ADR-2607072300). Entities are
namespaced by source: `gp:<PATENT-ID>` (e.g. `gp:US8697359B1`). Attributes:

`:patent/source`, `:patent/source-url`, `:patent/title`, `:patent/number`,
`:patent/patent-id`, `:patent/country`, `:patent/application-number`,
`:patent/filed-at`, `:patent/granted-at`, `:patent/inventor` (many),
`:patent/applicant` (many), `:patent/cites` (many — citation-graph edges),
`:patent/retrieved-at`.

## Worldwide coverage

Asymptotic and honest (the workspace's system-dynamics rule): the seeds plus
the citation graph grow coverage over time, not a claim of complete global
inventory on day one. v1 proves the loop with US/EP roots and their citation
graphs; JPO standardized-data TSV and EPO DOCDB bulk ingestion (the eventual
~150M-record worldwide master) are follow-ons — heavy backfills can fan out via
`murakumo task run` (ADR-2607256000, the fleet task plane). See ADR-2607251552.
