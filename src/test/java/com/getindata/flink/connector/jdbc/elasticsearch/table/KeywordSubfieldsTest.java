package com.getindata.flink.connector.jdbc.elasticsearch.table;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeywordSubfieldsTest {

    private static final KeywordSubfields NAME_KEYWORD_SUBFIELD = KeywordSubfields.of(Map.of("name", "name.keyword"));

    @Test
    void shouldReturnKeywordSubfieldOfStringColumn() {
        assertThat(NAME_KEYWORD_SUBFIELD.keywordSubfieldOf("name", DataTypes.STRING())).contains("name.keyword");
        assertThat(NAME_KEYWORD_SUBFIELD.keywordSubfieldOf("name", DataTypes.VARCHAR(10))).contains("name.keyword");
        assertThat(NAME_KEYWORD_SUBFIELD.keywordSubfieldOf("other", DataTypes.STRING())).isEmpty();
    }

    @Test
    void shouldIgnoreColumnThatIsNotAString() {
        assertThat(NAME_KEYWORD_SUBFIELD.keywordSubfieldOf("name", DataTypes.BIGINT())).isEmpty();
    }

    @Test
    void noneShouldHaveNoKeywordSubfields() {
        assertThat(KeywordSubfields.NONE.keywordSubfieldOf("name", DataTypes.STRING())).isEmpty();
    }

    @Test
    void shouldNotReadMetadataForColumnThatIsNotAString() {
        // reading from the unreachable Elasticsearch would fail
        var unreadable = KeywordSubfields.read(SourceTableUtils.unreachableOptions());

        assertThat(unreadable.keywordSubfieldOf("flag", DataTypes.BOOLEAN())).isEmpty();
    }

    @Test
    void shouldFailWhenMetadataCannotBeRead() {
        var unreadable = KeywordSubfields.read(SourceTableUtils.unreachableOptions());

        assertThatThrownBy(() -> unreadable.keywordSubfieldOf("name", DataTypes.STRING()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Could not read the columns of Elasticsearch table 'idx'");
    }

    @Test
    void shouldBeEqualForSameOptionsOrSubfields() {
        var options = SourceTableUtils.unreachableOptions();

        assertThat(KeywordSubfields.read(options)).isEqualTo(KeywordSubfields.read(options));
        assertThat(KeywordSubfields.read(options)).isNotEqualTo(KeywordSubfields.NONE);
        assertThat(KeywordSubfields.of(Map.of("name", "name.keyword"))).isEqualTo(NAME_KEYWORD_SUBFIELD);
        assertThat(NAME_KEYWORD_SUBFIELD).isNotEqualTo(KeywordSubfields.NONE);
    }
}
