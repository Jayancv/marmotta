/**
 * Copyright (C) 2013 Salzburg Research.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.marmotta.kiwi.persistence;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import org.apache.marmotta.kiwi.caching.KiWiCacheManager;
import org.apache.marmotta.kiwi.model.rdf.KiWiNode;
import org.apache.marmotta.kiwi.model.rdf.KiWiResource;
import org.apache.marmotta.kiwi.model.rdf.KiWiUriResource;
import org.apache.marmotta.kiwi.persistence.util.ScriptRunner;
import org.openrdf.model.Statement;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyVetoException;
import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Add file description here!
 * <p/>
 * Author: Sebastian Schaffert
 */
public class KiWiPersistence {

    private static Logger log = LoggerFactory.getLogger(KiWiPersistence.class);

    /**
     * A unique name for identifying this instance of KiWiPersistence. Can be used in case there are several
     * instances running in the same environment.
     */
    private String name;


    /**
     * The connection pool for managing JDBC connections
     */
    private ComboPooledDataSource connectionPool;

    /**
     * The SQL dialect to use
     */
    private KiWiDialect           dialect;


    private KiWiCacheManager      cacheManager;

    private Set<Connection>       managedConnections;

    private KiWiGarbageCollector  garbageCollector;

    public KiWiPersistence(String name, String jdbcUrl, String db_user, String db_password, KiWiDialect dialect) {
        this.name    = name;
        this.dialect = dialect;

        // init JDBC connection pool
        initConnectionPool(jdbcUrl, db_user, db_password);

        // init EHCache caches
        initCachePool();

        // init garbage collector thread
        initGarbageCollector();

        try {
            logPoolInfo();
        } catch (SQLException e) {

        }

    }

    public KiWiDialect getDialect() {
        return dialect;
    }

    public KiWiCacheManager getCacheManager() {
        return cacheManager;
    }


    private void initCachePool() {
        cacheManager = new KiWiCacheManager(name);
    }


    private void initConnectionPool(String jdbcUrl, String db_user, String db_password) {
        managedConnections = Collections.newSetFromMap(new WeakHashMap<Connection, Boolean>());

        connectionPool = new ComboPooledDataSource();
        try {
            connectionPool.setDriverClass(dialect.getDriverClass());
            connectionPool.setJdbcUrl(jdbcUrl);
            connectionPool.setUser(db_user);
            connectionPool.setPassword(db_password);

            connectionPool.setMinPoolSize(5);
            connectionPool.setMaxPoolSize(20);
            connectionPool.setAcquireIncrement(5);
            connectionPool.setIdleConnectionTestPeriod(300);
            connectionPool.setMaxStatements(100);
            connectionPool.setMaxIdleTime(600);

            // debug
            if("true".equals(System.getProperty("debug.database"))) {
                connectionPool.setUnreturnedConnectionTimeout(60);
                connectionPool.setDebugUnreturnedConnectionStackTraces(true);
            }
        } catch (PropertyVetoException e) {
            log.error("could not initialise JDBC connection pool",e);
        }
    }

    private void initGarbageCollector() {
        this.garbageCollector = new KiWiGarbageCollector(this);

        garbageCollector.addNodeTableDependency("triples","subject");
        garbageCollector.addNodeTableDependency("triples","predicate");
        garbageCollector.addNodeTableDependency("triples","object");
        garbageCollector.addNodeTableDependency("triples","context");
        garbageCollector.addNodeTableDependency("triples","creator");
        garbageCollector.addNodeTableDependency("nodes","ltype");

    }

    public void logPoolInfo() throws SQLException {
        log.info("num_connections: {}", connectionPool.getNumConnectionsDefaultUser());
        log.info("num_busy_connections: {}", connectionPool.getNumBusyConnectionsDefaultUser());
        log.info("num_idle_connections: {}", connectionPool.getNumIdleConnectionsDefaultUser());

    }


    public void initDatabase() throws SQLException {
        initDatabase("base", new String[] {"nodes", "triples", "namespaces","metadata"});
    }


    /**
     * Initialise the database, creating or upgrading tables if they do not exist or are of the wrong version.
     *
     * @param scriptName the name of the script to use for create or update (e.g. "base" or "versioning")
     */
    public void initDatabase(String scriptName, String[] checkTables) throws SQLException {
        // get a database connection and check which version the database is (if it exists)
        KiWiConnection connection = getConnection();
        try {
            Set<String> tables = connection.getDatabaseTables();

            if(log.isDebugEnabled()) {
                log.debug("database tables:");
                for(String table : tables) {
                    log.debug("- found table: {}",table);
                }
            }

            // check existence of all tables; if the necessary tables are not there, they need to be created
            boolean createNeeded = false;
            for(String tableName : checkTables) {
                createNeeded = createNeeded || !tables.contains(tableName);
            }
            if(createNeeded) {
                log.info("creating new KiWi database ...");

                ScriptRunner runner = new ScriptRunner(connection.getJDBCConnection(), false, false);
                runner.runScript(new StringReader(dialect.getCreateScript(scriptName)));

            } else {
                int version = connection.getDatabaseVersion();

                String updateScript = dialect.getMigrationScript(version,scriptName);
                if(updateScript != null) {
                    log.info("upgrading existing KiWi database from version {} to version {}", version, dialect.getVersion());

                    ScriptRunner runner = new ScriptRunner(connection.getJDBCConnection(), false, false);
                    runner.runScript(new StringReader(updateScript));

                } else {
                    log.info("connecting to existing KiWi database (version: {})",version);
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            log.error("SQL exception while initialising database, rolling back");
            connection.rollback();
            throw ex;
        } catch (IOException ex) {
            log.error("I/O exception while initialising database, rolling back");
            connection.rollback();
        } finally {
            connection.close();
        }
    }

    /**
     * Remove all KiWi base tables from the SQL database. This method will run the drop script of the respective dialect and
     * return.
     *
     * @throws SQLException
     */
    public void dropDatabase() throws SQLException {
        dropDatabase("base");
    }

    /**
     * Remove all KiWi tables from the SQL database. This method will run the drop script of the respective dialect and
     * return.
     *
     *
     * @param scriptName the name of the script to use for drop (e.g. "base" or "versioning")
     * @throws SQLException
     */
    public void dropDatabase(final String scriptName) throws SQLException {
        // we start this in a separate thread because there might still be a lock on the database tables
        forceCloseConnections();

        try {
            // get a database connection and check which version the database is (if it exists)
            KiWiConnection connection = getConnection();
            try {
                Set<String> tables = connection.getDatabaseTables();

                if(log.isDebugEnabled()) {
                    log.debug("BEFORE DROP: database tables");
                    for(String table : tables) {
                        log.debug("- found table: {}",table);
                    }
                }

                ScriptRunner runner = new ScriptRunner(connection.getJDBCConnection(), false, false);
                runner.runScript(new StringReader(dialect.getDropScript(scriptName)));


                if(log.isDebugEnabled()) {
                    tables = connection.getDatabaseTables();
                    log.debug("AFTER DROP: database tables");
                    for(String table : tables) {
                        log.debug("- found table: {}",table);
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                log.error("SQL exception while dropping database, rolling back");
                connection.rollback();
                throw ex;
            } catch (IOException ex) {
                log.error("I/O exception while dropping database, rolling back");
                connection.rollback();
            } finally {
                connection.close();
            }
        } catch(SQLException ex) {
            log.error("SQL exception while acquiring database connection");
        }
    }

    /**
     * Return a connection from the connection pool which already has the auto-commit disabled.
     *
     * @return a fresh JDBC connection from the connection pool
     * @throws SQLException in case a new connection could not be established
     */
    public KiWiConnection getConnection() throws SQLException {
        Connection conn = connectionPool.getConnection();
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        managedConnections.add(conn);

        return new KiWiConnection(conn,dialect,cacheManager);
    }

    /**
     * Return a raw JDBC connection from the connection pool, which already has the auto-commit disabled.
     * @return
     * @throws SQLException
     */
    public Connection getJDBCConnection() throws SQLException {
        Connection conn = connectionPool.getConnection();
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        managedConnections.add(conn);

        return conn;
    }


    private void forceCloseConnections() {
        for(Connection conn : managedConnections) {
            try {
                if(!conn.isClosed()) {
                    log.warn("enforce closing of open connection!");
                    if(!conn.getAutoCommit()) {
                        conn.rollback();
                    }
                    conn.close();
                }
            } catch (SQLException e) {
                log.error("error while enforcing closing of open connection",e);
            }
        }
    }

    /**
     * Add information about a dependency of a column in some table to the "nodes" table; this information
     * is used when cleaning up unreferenced deleted entries in the nodes table. In theory, we could
     * get this information from the database, but each database has a very different way of doing this, so
     * it is easier to simply let dependent modules register this information.
     * @param tableName
     * @param columnName
     */
    public void addNodeTableDependency(String tableName, String columnName) {
        garbageCollector.addNodeTableDependency(tableName, columnName);
    }

    /**
     * Add information about a dependency of a column in some table to the "triples" table; this information
     * is used when cleaning up unreferenced deleted entries in the triples table. In theory, we could
     * get this information from the database, but each database has a very different way of doing this, so
     * it is easier to simply let dependent modules register this information.
     * @param tableName
     * @param columnName
     */
    public void addTripleTableDependency(String tableName, String columnName) {
        garbageCollector.addTripleTableDependency(tableName, columnName);
    }

    /**
     * Return a Sesame RepositoryResult of statements according to the query pattern given in the arguments. Each of
     * the parameters subject, predicate, object and context may be null, indicating a wildcard query. If the boolean
     * parameter "inferred" is set to true, the result will also include inferred triples, if it is set to false only
     * base triples.
     * <p/>
     * The RepositoryResult holds a direct connection to the database and needs to be closed properly, or otherwise
     * the system might run out of resources. The returned RepositoryResult will try its best to clean up when the
     * iteration has completed or the garbage collector calls the finalize() method, but this can take longer than
     * necessary.
     * <p/>
     * This method will create a new database connection for running the query which is only released when the
     * result is closed.
     *
     *
     * @param subject    the subject to query for, or null for a wildcard query
     * @param predicate  the predicate to query for, or null for a wildcard query
     * @param object     the object to query for, or null for a wildcard query
     * @param context    the context to query for, or null for a wildcard query
     * @param inferred   if true, the result will also contain triples inferred by the reasoner, if false not
     * @return a new RepositoryResult with a direct connection to the database; the result should be properly closed
     *         by the caller
     */
    public RepositoryResult<Statement> listTriples(KiWiResource subject, KiWiUriResource predicate, KiWiNode object, KiWiResource context, boolean inferred) throws SQLException {
        final KiWiConnection conn = getConnection();

        return new RepositoryResult<Statement>(conn.listTriples(subject,predicate,object,context,inferred)) {
            @Override
            protected void handleClose() throws RepositoryException {
                super.handleClose();
                try {
                    if(!conn.isClosed()) {
                        conn.commit();
                        conn.close();
                    }
                } catch (SQLException ex) {
                    throw new RepositoryException("SQL error when closing database connection",ex);
                }
            }

            @Override
            protected void finalize() throws Throwable {
                handleClose();
                super.finalize();
            }
        };
    }


    public void initialise() {
        garbageCollector.start();
    }

    public void shutdown() {
        garbageCollector.shutdown();
        cacheManager.shutdown();
        connectionPool.close();
    }

    /**
     * Remove all elements from the cache
     */
    public void clearCache() {
        cacheManager.clear();
    }


}