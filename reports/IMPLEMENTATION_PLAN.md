# First release implementation plan

1. Extend the existing novel plugin, preserving upstream behavior when archive is disabled. Reuse the native HTTP adapter, metadata parser, novel downloader and exporters.
2. Add one host-retained journal table in the existing database. Stable keys are target keyword, novel ID and series ID; payload schema version is independent of application version. Reject unknown future versions without overwriting them. No reset on release upgrade.
3. Persist search range/page and discovered novel IDs in one transaction. Process a bounded page before admitting more. Split oversized date ranges; explicitly flag an unsplittable capped day as incomplete.
4. Freeze each first-discovered series chapter selection. Metadata filtering applies to every selected chapter. Persist skips, retry state, series export state and global network cooldown.
5. Provide a disabled-by-default JSON configuration, dry run, admin status/pause/resume endpoints and Windows background launcher with a stable external runtime directory. Credentials stay outside the journal.
6. Add explicit selection to merge, atomic publication and archive sidecar metadata. Default embedded images off; retain text markers. Do not delete pre-existing files.
7. Verify with fixtures and SQLite restart/upgrade tests, mocked HTTP and exporter tests. Build with Maven Wrapper; record limitations and test results.

Core changes are restricted to retaining archive progress through plugin/release changes and exposing Retry-After through the existing HTTP exception API. No alternative crawler framework or second archive database is introduced.
