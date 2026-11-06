package com.getindata.flink.connector.jdbc.elasticsearch.database.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchCatalogQueriesTest {

    @Test
    void shouldQuoteTableInMetadataQuery() {
        assertThat(ElasticsearchCatalog.tableMetadataQuery("idx-orders.details"))
                .isEqualTo("SELECT * FROM \"idx-orders.details\" LIMIT 0");
    }

    @Test
    void shouldQuoteTableInPartitionNumberQuery() {
        assertThat(ElasticsearchCatalog.partitionNumberQuery("idx", "100"))
                .isEqualTo("SELECT CEIL(COUNT(*) / 100) FROM \"idx\"");
    }

    @Test
    void shouldQuoteBothColumnReferencesInNumericBoundsQuery() {
        assertThat(ElasticsearchCatalog.partitionBoundsQuery("idx", "metrics.value", false))
                .isEqualTo(
                        "SELECT MIN(\"metrics.value\") AS scanPartitionLowerBound, "
                                + "MAX(\"metrics.value\") AS scanPartitionUpperBound FROM \"idx\"");
    }

    @Test
    void shouldQuoteColumnInTemporalBoundsQuery() {
        assertThat(ElasticsearchCatalog.partitionBoundsQuery("idx", "@timestamp", true))
                .isEqualTo(
                        "SELECT COALESCE(MIN(\"@timestamp\"), CURRENT_DATE) AS scanPartitionLowerBound, "
                                + "DATE_ADD('days', 1, CURRENT_DATE) AS scanPartitionUpperBound "
                                + "FROM \"idx\"");
    }

    @Test
    void shouldEscapeDoubleQuotesInPartitionColumn() {
        assertThat(ElasticsearchCatalog.partitionBoundsQuery("idx", "count\"s", false))
                .isEqualTo(
                        "SELECT MIN(\"count\"\"s\") AS scanPartitionLowerBound, "
                                + "MAX(\"count\"\"s\") AS scanPartitionUpperBound FROM \"idx\"");
        assertThat(ElasticsearchCatalog.partitionBoundsQuery("idx", "event\"time", true))
                .startsWith("SELECT COALESCE(MIN(\"event\"\"time\"), CURRENT_DATE)");
    }

    @Test
    void shouldKeepOtherSpecialCharactersInPartitionColumn() {
        assertThat(ElasticsearchCatalog.partitionBoundsQuery("idx", "geo:loc?", false))
                .isEqualTo(
                        "SELECT MIN(\"geo:loc?\") AS scanPartitionLowerBound, "
                                + "MAX(\"geo:loc?\") AS scanPartitionUpperBound FROM \"idx\"");
    }
}
