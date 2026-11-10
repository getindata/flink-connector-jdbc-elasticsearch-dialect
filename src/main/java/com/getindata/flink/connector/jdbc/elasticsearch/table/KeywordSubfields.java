package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.connector.jdbc.internal.options.InternalJdbcConnectionOptions;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeFamily;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.Optional;

@Slf4j
@EqualsAndHashCode
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class KeywordSubfields {

    static final KeywordSubfields NONE = of(Map.of());

    @Nullable
    private final InternalJdbcConnectionOptions options;
    @Nullable
    private final Map<String, String> givenSubfields;
    @Nullable
    @EqualsAndHashCode.Exclude
    private Map<String, String> readSubfields;

    // read on first use, as most queries reference no string column
    static KeywordSubfields read(InternalJdbcConnectionOptions options) {
        return new KeywordSubfields(options, null);
    }

    static KeywordSubfields of(Map<String, String> subfields) {
        return new KeywordSubfields(null, Map.copyOf(subfields));
    }

    Optional<String> keywordSubfieldOf(String column, DataType type) {
        if (!type.getLogicalType().is(LogicalTypeFamily.CHARACTER_STRING)) {
            return Optional.empty();
        }
        return Optional.ofNullable(subfields().get(column));
    }

    private Map<String, String> subfields() {
        if (givenSubfields != null) {
            return givenSubfields;
        }
        if (readSubfields == null) {
            readSubfields = EsColumnMetadata.singleKeywordSubfieldsOfTextColumns(options);
            LOG.debug("Conditions on text columns with a single keyword subfield of {} are rechecked by Flink: {}",
                    options.getTableName(), readSubfields);
        }
        return readSubfields;
    }
}
