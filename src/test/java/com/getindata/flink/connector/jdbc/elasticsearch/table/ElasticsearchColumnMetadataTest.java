package com.getindata.flink.connector.jdbc.elasticsearch.table;

import com.getindata.flink.connector.jdbc.elasticsearch.database.dialect.ElasticsearchDialect;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.table.api.ValidationException;
import org.junit.jupiter.api.Test;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchColumnMetadataTest {

    @Test
    void shouldMapTextColumnsToTheirKeywordSubfield() throws Exception {
        var metaData =
                metaData(
                        "RawXml", "TEXT",
                        "RawXml.keyword", "KEYWORD",
                        "RawXml.english", "TEXT",
                        "plain_text", "TEXT",
                        "kw", "KEYWORD",
                        "id", "LONG",
                        // dotted names of object fields are not subfields
                        "order.details.referenceId", "KEYWORD",
                        "order", "OBJECT",
                        // prefix of another column without the dot
                        "name", "TEXT",
                        "name_keyword", "KEYWORD");

        var columns =
                EsColumnMetadata.readTextColumnsWithKeywordSubfield(metaData, "idx");

        assertThat(columns)
                .containsOnly(Map.entry("RawXml", "RawXml.keyword"));
    }

    @Test
    void shouldSkipTextColumnsWithSeveralKeywordSubfields() throws Exception {
        var metaData =
                metaData(
                        "multi", "TEXT",
                        "multi.keyword", "KEYWORD",
                        "multi.raw", "KEYWORD");

        assertThat(EsColumnMetadata.readTextColumnsWithKeywordSubfield(metaData, "idx")).isEmpty();
    }

    @Test
    void shouldReturnEmptyMapWithoutTextColumns() throws Exception {
        var metaData = metaData("kw", "KEYWORD", "id", "LONG");

        assertThat(EsColumnMetadata.readTextColumnsWithKeywordSubfield(metaData, "idx")).isEmpty();
    }

    @Test
    void shouldEscapeLikeWildcardsInTableName() throws Exception {
        var metaData = metaData();

        EsColumnMetadata.readTextColumnsWithKeywordSubfield(metaData, "my_index-*");

        verify(metaData).getColumns(isNull(), isNull(), eq("my\\_index-*"), isNull());
    }

    @Test
    void shouldEscapeLikeWildcards() {
        assertThat(EsColumnMetadata.escapeLikeWildcards("my_index", "\\")).isEqualTo("my\\_index");
        assertThat(EsColumnMetadata.escapeLikeWildcards("a%b\\c", "\\")).isEqualTo("a\\%b\\\\c");
        assertThat(EsColumnMetadata.escapeLikeWildcards("logs-*", "\\")).isEqualTo("logs-*");
        assertThat(EsColumnMetadata.escapeLikeWildcards("my_index", "")).isEqualTo("my_index");
    }

    @Test
    void shouldFailWithValidationExceptionWhenMetadataCannotBeRead() {
        var options =
                InternalJdbcConnectionOptions.builder()
                        .setDBUrl("jdbc:elasticsearch://localhost:1")
                        .setTableName("docs")
                        .setDialect(new ElasticsearchDialect())
                        .build();

        assertThatThrownBy(() -> EsColumnMetadata.readTextColumnsWithKeywordSubfield(options))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Could not read the columns of Elasticsearch table 'docs'")
                .hasCauseInstanceOf(java.sql.SQLException.class);
    }

    private static DatabaseMetaData metaData(String... namesAndTypes) throws Exception {
        var metaData = mock(DatabaseMetaData.class);
        var resultSet = mock(ResultSet.class);
        int[] row = {-1};
        when(resultSet.next()).thenAnswer(invocation -> ++row[0] < namesAndTypes.length / 2);
        when(resultSet.getString("COLUMN_NAME")).thenAnswer(invocation -> namesAndTypes[2 * row[0]]);
        when(resultSet.getString("TYPE_NAME")).thenAnswer(invocation -> namesAndTypes[2 * row[0] + 1]);
        when(metaData.getSearchStringEscape()).thenReturn("\\");
        when(metaData.getColumns(isNull(), isNull(), anyString(), isNull())).thenReturn(resultSet);
        return metaData;
    }
}
