# Baseline audit

| Capability | Existing code | Gap / action |
|---|---|---|
| Search | PixivScheduledSourceSupport, discoverSearch | Watermark / completed-boundary scans start at page 1. Archive needs durable page admission plus historical date ranges. |
| Novel metadata | PixivNovelMetadata, NovelDownloadRequestFactory | Reuse parsing and request construction, require actual tags before admission. |
| Downloads | NovelDownloadService, NovelDatabase | Reuse output and shared novel history. No second content database. |
| Merge | NovelMergeService | Existing merge selects all locally stored series chapters; add explicit selected-chapter archive export. |
| Persistence | Host NovelSchemaContribution, shared SqlSessionFactory | Add host-retained archive journal alongside novels; preserve on plugin disable/uninstall. |
| Recovery sentinel | RecoverySentinelPlugin | Plugin availability probe, not download recovery. |
| PBD | AutoMergeNovel, MergeNovel, Resume, Filter, DownloadInterval | Useful behavioral references; no copied code. Resume stores download results, not durable search pagination. saveAllSeriesNovelsIfOneMatches can bypass filtering; do not reproduce. |

Scope: one global exact normalized blacklist; cross-tag novel/series deduplication; first official 10 positions, no replacement for excluded chapters; no series refresh; cover images only; durable slow background work. No production Pixiv requests during development.
