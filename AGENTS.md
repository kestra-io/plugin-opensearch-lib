# AGENTS.md

## What

- Shared kernel library published as `io.kestra.plugin:plugin-opensearch-lib`, consumed by `plugin-opensearch` (OSS) and `plugin-ee-opensearch` (EE). It is **not** a loadable Kestra plugin — it ships no task, no trigger, no plugin docs or icons, and cannot be registered on a running Kestra instance on its own.
- Provides classes under `io.kestra.plugin.opensearch.shared`: `OpensearchConnection` (hosts, basic auth, headers, TLS trust, path prefix, strict deprecation mode) and `BulkService` (buffered bulk indexing with `requests.count` / `records` / `requests.duration` metrics).

## Why

- OSS and EE each shipped a near-verbatim, but drifted, copy of the connection-building and bulk-indexing code. This library removes that duplication so both consumers evolve the connection and bulk logic in one place instead of drifting further apart.

## Local rules

- This library is shared between OSS and EE **only** — it is not a general-purpose OpenSearch client wrapper. Do not add task/trigger classes, plugin docs, or plugin icons here; each consumer keeps its own.
- It is a plain library, not a plugin: no `package-info.java` with `@PluginSubGroup`, no `shadowJar`, no `X-Kestra-*` jar manifest, no `Dockerfile`/`docker-compose.yml`.
- This library is the single source of the OpenSearch client version (inherited from the `io.kestra:platform` BOM); neither consumer declares it anymore. Both inherit it transitively through the `api` dependency, so bumping it here changes the `connection` schema for OSS and EE simultaneously — bump deliberately and QA both.
- Preserve metric names (`requests.count`, `records`, `requests.duration`) and error message text byte-for-byte; both consumers' users template outputs and grep logs on these.
- Must be released to Maven Central before either consumer can pin a released (non-`SNAPSHOT`) version; until then, consumers resolve it from `mavenLocal()`/the Sonatype snapshots repository.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
