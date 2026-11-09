package com.getindata.flink.connector.jdbc.elasticsearch.table;

import lombok.experimental.UtilityClass;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalTypeFamily;

@UtilityClass
class EsTypes {

    boolean isStringType(DataType type) {
        return type.getLogicalType().is(LogicalTypeFamily.CHARACTER_STRING);
    }
}
