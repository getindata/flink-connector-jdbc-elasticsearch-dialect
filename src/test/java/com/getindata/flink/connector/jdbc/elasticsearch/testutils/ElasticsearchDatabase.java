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

package com.getindata.flink.connector.jdbc.elasticsearch.testutils;

import org.apache.flink.connector.jdbc.testutils.DatabaseExtension;
import org.apache.flink.connector.jdbc.testutils.DatabaseMetadata;
import org.apache.flink.connector.jdbc.testutils.DatabaseResource;
import org.apache.flink.util.FlinkRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import static com.getindata.flink.connector.jdbc.elasticsearch.testutils.ElasticsearchMetadata.PASSWORD;


/**
 * Elasticsearch database for testing.
 */
public class ElasticsearchDatabase extends DatabaseExtension implements ElasticsearchImages {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchDatabase.class);
    private static final ElasticsearchContainer CONTAINER = new ElasticsearchContainer(ELASTICSEARCH_8)
            .waitingFor(Wait.forLogMessage(".*license mode is .*", 1))
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));

    private static ElasticsearchMetadata metadata;
    private static ElasticsearchRestClient client;

    public static ElasticsearchMetadata getMetadata() {
        if (!CONTAINER.isRunning()) {
            throw new FlinkRuntimeException("Container is stopped.");
        }
        if (metadata == null) {
            metadata = new ElasticsearchMetadata(CONTAINER);
        }
        return metadata;
    }

    private static ElasticsearchRestClient getClient() {
        if (!CONTAINER.isRunning()) {
            throw new FlinkRuntimeException("Container is stopped.");
        }
        if (client == null) {
            client = new ElasticsearchRestClient(getMetadata());
        }
        return client;
    }

    @Override
    protected DatabaseMetadata getMetadataDB() {
        return getMetadata();
    }

    @Override
    protected DatabaseResource getResource() {
        return new ElasticDatabaseResource(CONTAINER);
    }

    private static class ElasticDatabaseResource implements DatabaseResource {

        private final ElasticsearchContainer container;

        private ElasticDatabaseResource(ElasticsearchContainer container) {
            this.container = container;
        }

        @Override
        public void start() {
            container.withEnv("xpack.security.enabled", "true");
            container.withEnv("action.destructive_requires_name", "false");
            container.withEnv("ELASTIC_PASSWORD", PASSWORD);
            container.withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g");
            container.start();

            try {
                // JDBC plugin is available only in Platinum and Enterprise licenses or in trial.
                if (!getClient().trialEnabled()) {
                    getClient().enableTrial();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void stop() {
            container.stop();
            metadata = null;
            client = null;
        }
    }
}