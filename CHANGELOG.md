# Changelog

## [Unreleased]

## [0.5.0] - 2026-11-09

-   Fix lookup join on tables and columns whose names contain special characters
    (e.g. `a.b.c`, `@xyz`, `a:b`, `a?b`, cross-cluster `cluster:index`). The lookup query now uses
    positional parameters, like the scan, instead of JDBC named parameters. Catalog tables now use
    the new `elasticsearch-jdbc` connector, which accepts the same options as `jdbc`.
    Tables defined with DDL must use `'connector' = 'elasticsearch-jdbc'` to get the fix,
    `'connector' = 'jdbc'` still uses the upstream lookup.
-   The lookup function of `elasticsearch-jdbc` reports its source table to Flink lineage,
    like the upstream `jdbc` lookup
-   Escape double quotes in quoted identifiers (`a"b` → `"a""b"`), so columns containing `"`
    work in scan, filter push down and lookup join
-   Fix quoting of the catalog queries for the table schema and the scan partition bounds:
    the upper bound used an unquoted column name and `"` in table or column names was not escaped
-   Add the `lombok` dependency (`provided`), previously only configured as annotation processor
-   Restore the `mockito-core` test dependency (5.23.0), used by the new unit tests

## [0.4.0] - 2026-08-21

-   Upgrade to Flink 2.2.1 and flink-connector-jdbc 4.1.0-2.2
-   Raise the minimum required Java version to 17
-   Upgrade Elasticsearch client/driver and the test image to 8.19.20
-   Upgrade Testcontainers to 2.0.5 (fixes Docker environment detection on recent Docker releases)
-   Upgrade JUnit, AssertJ, Jackson, logback, SLF4J, OkHttp and the Maven plugins
-   Declare `flink-connector-base` and `flink-table-api-java-bridge` explicitly; both are `provided`
    in flink-connector-jdbc-core and were previously missing from the test classpath
-   Remove the unused `mockito-core` test dependency
-   Fix the `scm` connection protocol and drop the retired OSSRH `distributionManagement` block

## [0.3.0] - 2025-08-08

-   Upgrade to Flink 2.0

## [0.2.1] - 2024-04-11

## [0.2.0] - 2023-11-22

## [0.1.0] - 2023-11-22

-   Initial implementation of Elasticsearch SQL Dialect for flink-connector-jdbc

[Unreleased]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.5.0...HEAD

[0.5.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.4.0...0.5.0

[0.4.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.3.0...0.4.0

[0.3.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.2.1...0.3.0

[0.2.1]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.2.0...0.2.1

[0.2.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/0.1.0...0.2.0

[0.1.0]: https://github.com/getindata/flink-connector-jdbc-elasticsearch-dialect/compare/bf6a3dabc150a57fc241741efd0f40600adbca45...0.1.0
