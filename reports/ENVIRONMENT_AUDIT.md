# Environment audit

- Windows native PowerShell; workspace E:\AI\PixivDownloader was an empty Git repository.
- Imported local clean upstream E:\Git\PixivDownloader at 39b997df; branch codex/novel-archive.
- Reference PBD E:\Git\PixivBatchDownloader at e0da1a33 (read only).
- Java was absent from PATH. A checksum-verified portable Temurin JDK 17 was downloaded into ignored .tools; no system Java settings changed.
- Use upstream mvnw.cmd; no WSL or Docker required.
- No user runtime database, downloads, credentials or configuration copied or modified.

## Final verification environment

- Final compile, targeted regression tests, package and real headless startup were run under WSL with OpenJDK 17, Maven 3.9.11 and Node.js 22.23.1 in an isolated /tmp copy.
- Initial Windows native compilation succeeded; PowerShell scripts were parsed by native Windows PowerShell. Final Windows sleep/wake and live Pixiv access remain untested.
- A temporary loopback Maven mirror forwarded requests to Maven Central over verified HTTPS to work around Java TLS failures; this is not part of the delivered runtime.
- Only source, reports and build artifacts are synchronized to E:\AI\PixivDownloader; no smoke-test database or credentials are delivered.
