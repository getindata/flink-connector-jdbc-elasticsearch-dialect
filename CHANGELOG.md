# Changelog

## [Unreleased]

-   Fix lookup join on tables and columns whose names contain special characters
    (e.g. `a.b.c`, `@xyz`, `a:b`, `a?b`, cross-cluster `cluster:index`). The lookup query now uses
    positional parameters, like the scan, instead of JDBC named parameters. Catalog tables now use
    the new `elasticsearch-jdbc` connector, which accepts the same options as `jdbc`.
    Tables defined with DDL must use `'connector' = 'elasticsearch-jdbc'` to get the fix,
    `'connector' = 'jdbc'` still uses the upstream lookup.
-   Upgrade Testcontainers to 1.21.4 to support recent Docker versions

## [0.3.0] - 2025-08-08

-   Upgrade to Flink 2.0

## [0.2.1] - 2024-04-11

## [0.2.0] - 2023-11-22

## [0.1.0] - 2023-11-22

-   Initial implementation of Elasticsearch SQL Dialect for flink-connector-jdbc

[Unreleased]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.3.0...HEAD

[0.3.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.2.1...0.3.0

[0.2.1]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.2.0...0.2.1

[0.2.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.1.0...0.2.0

[0.1.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/bf6a3dabc150a57fc241741efd0f40600adbca45...0.1.0
