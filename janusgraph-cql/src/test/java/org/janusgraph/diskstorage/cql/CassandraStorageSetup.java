// Copyright 2017 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.diskstorage.cql;

import static org.janusgraph.diskstorage.cql.CQLConfigOptions.KEYSPACE;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.PROTOCOL_VERSION;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_ENABLED;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_TRUSTSTORE_LOCATION;
import static org.janusgraph.diskstorage.cql.CQLConfigOptions.SSL_TRUSTSTORE_PASSWORD;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.CONNECTION_TIMEOUT;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.PAGE_SIZE;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.STORAGE_BACKEND;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.STORAGE_HOSTS;
import static org.janusgraph.graphdb.configuration.GraphDatabaseConfiguration.buildGraphConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;

import org.apache.commons.io.FileUtils;
import org.janusgraph.diskstorage.cassandra.utils.CassandraDaemonWrapper;
import org.janusgraph.diskstorage.configuration.ModifiableConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

public class CassandraStorageSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraStorageSetup.class);

    private static final String TEST_STORAGE_HOST = "test.storage.host";

    public static final String CONFDIR_SYSPROP = "test.cassandra.confdir";
    public static final String DATADIR_SYSPROP = "test.cassandra.datadir";

    private static volatile Paths paths;

    /**
     * Load cassandra.yaml and data paths from the environment or from default values if nothing is set in the environment, then delete all
     * existing data, and finally start Cassandra.
     * <p>
     * This method is idempotent. Calls after the first have no effect aside from logging statements.
     */
    public static void startCleanEmbedded() {
        startCleanEmbedded(false);
    }

    public static void startCleanEmbedded(boolean force) {
        if (force || System.getProperty(TEST_STORAGE_HOST) == null) {
            final Paths p = getPaths();
            if (!CassandraDaemonWrapper.isStarted()) {
                try {
                    FileUtils.deleteDirectory(new File(p.dataPath));
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            }
            CassandraDaemonWrapper.start(p.yamlPath);
        }
    }

    private static synchronized Paths getPaths() {
        if (null == paths) {
            final String yamlPath = "file://" + loadAbsoluteDirectoryPath("conf", CONFDIR_SYSPROP, true) + File.separator
                    + "cassandra.yaml";
            final String dataPath = loadAbsoluteDirectoryPath("data", DATADIR_SYSPROP, false);
            paths = new Paths(yamlPath, dataPath);
        }
        return paths;
    }

    private static String loadAbsoluteDirectoryPath(final String name, final String prop, final boolean mustExistAndBeAbsolute) {
        String s = System.getProperty(prop);

        if (null == s) {
            s = Joiner.on(File.separator).join(System.getProperty("user.dir"), "target", "cassandra", "byteorderedpartitioner", name);
            LOGGER.info("Set default Cassandra {} directory path {}", name, s);
        } else {
            LOGGER.info("Loaded Cassandra {} directory path {} from system property {}", new Object[] { name, s, prop });
        }

        if (mustExistAndBeAbsolute) {
            final File dir = new File(s);
            Preconditions.checkArgument(dir.isDirectory(), "Path %s must be a directory", s);
            Preconditions.checkArgument(dir.isAbsolute(), "Path %s must be absolute", s);
        }

        return s;
    }

    public static ModifiableConfiguration getCQLConfiguration(final String keyspace) {
        final ModifiableConfiguration config = buildGraphConfiguration();
        config.set(KEYSPACE, cleanKeyspaceName(keyspace));
        LOGGER.debug("Set keyspace name: {}", config.get(KEYSPACE));
        config.set(PAGE_SIZE, 500);
        config.set(CONNECTION_TIMEOUT, Duration.ofSeconds(60L));
        config.set(STORAGE_BACKEND, "cql");
        // Set to 3 because we have a 2.1.9 database that only supports version 3, if we let it negotiate then there are spurious errors.
        config.set(PROTOCOL_VERSION, 3);
        if (System.getProperty(TEST_STORAGE_HOST) != null) {
            config.set(STORAGE_HOSTS, new String[] { System.getProperty(TEST_STORAGE_HOST) });
        }
        return config;
    }

    public static ModifiableConfiguration enableSSL(final ModifiableConfiguration mc) {
        mc.set(SSL_ENABLED, true);
        mc.set(STORAGE_HOSTS, new String[] { "localhost" });
        mc.set(SSL_TRUSTSTORE_LOCATION,
                Joiner.on(File.separator).join("target", "cassandra", "murmur-ssl", "conf", "test.truststore"));
        mc.set(SSL_TRUSTSTORE_PASSWORD, "cassandra");
        return mc;
    }

    /**
     * Cassandra only accepts keyspace names 48 characters long or shorter made up of alphanumeric characters and underscores.
     */
    private static String cleanKeyspaceName(final String raw) {
        Preconditions.checkNotNull(raw);
        Preconditions.checkArgument(0 < raw.length());

        if (48 < raw.length() || raw.matches("^.*[^a-zA-Z0-9_].*$")) {
            return "strhash" + String.valueOf(Math.abs(raw.hashCode()));
        } else {
            return raw;
        }
    }

    private static class Paths {

        private final String yamlPath;
        private final String dataPath;

        public Paths(final String yamlPath, final String dataPath) {
            this.yamlPath = yamlPath;
            this.dataPath = dataPath;
        }
    }
}
