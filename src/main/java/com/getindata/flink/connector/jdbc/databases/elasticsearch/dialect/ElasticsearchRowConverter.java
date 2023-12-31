/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.getindata.flink.connector.jdbc.databases.elasticsearch.dialect;

import org.apache.flink.connector.jdbc.converter.AbstractJdbcRowConverter;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;

import java.sql.Timestamp;

/**
 * Runtime converter that responsible to convert between JDBC object and Flink internal object for
 * Elasticsearch.
 */
public class ElasticsearchRowConverter extends AbstractJdbcRowConverter {
    private static final long serialVersionUID = 1L;

    public ElasticsearchRowConverter(RowType rowType) {
        super(rowType);
    }

    @Override
    public String converterName() {
        return "Elasticsearch";
    }

    @Override
    protected JdbcDeserializationConverter createInternalConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case TINYINT:
            case DOUBLE:
            case FLOAT:
                return val -> val;
            case DATE:
                return val ->
                        (int) (((Timestamp) val).toLocalDateTime().toLocalDate().toEpochDay());
            default:
                return super.createInternalConverter(type);
        }
    }
}