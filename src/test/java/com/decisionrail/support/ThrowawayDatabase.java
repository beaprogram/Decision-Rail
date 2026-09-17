package com.decisionrail.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A database created for one test and dropped again afterwards.
 *
 * <p>Some checks cannot be run against the suite's shared schema without damaging it for everything
 * else: migrating to an intermediate version and back, or seeding rows the current schema refuses.
 * Those get a database of their own on the same disposable test server, so nothing they do is visible
 * to another test and nothing survives the run.
 *
 * <p>It is created on whatever server {@code JDBC_URL} names, which in this project is the disposable
 * stack in {@code compose.test.yaml}. The development database is a different server on a different
 * port and is never touched by this.
 */
public final class ThrowawayDatabase implements AutoCloseable {
    private static final String ADMIN_DATABASE = "postgres";

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String name;

    private ThrowawayDatabase(String host, int port, String username, String password, String name) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.name = name;
    }

    /** Creates a uniquely named database. The caller closes it, which drops it. */
    public static ThrowawayDatabase create(String prefix) throws Exception {
        String url = System.getenv("JDBC_URL");
        if (url == null || url.isBlank()) url = "jdbc:postgresql://127.0.0.1:55433/decisionrail_test";
        String withoutScheme = url.substring("jdbc:postgresql://".length());
        String authority = withoutScheme.substring(0, withoutScheme.indexOf('/'));
        String host = authority.contains(":") ? authority.substring(0, authority.indexOf(':')) : authority;
        int port = authority.contains(":") ? Integer.parseInt(authority.substring(authority.indexOf(':') + 1)) : 5432;
        ThrowawayDatabase database = new ThrowawayDatabase(host, port,
                orDefault(System.getenv("JDBC_USERNAME"), "decisionrail"),
                orDefault(System.getenv("JDBC_PASSWORD"), "local-test-only"),
                prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        try (Connection admin = database.connectTo(ADMIN_DATABASE);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database.name);
        }
        return database;
    }

    public String url() { return urlFor(name); }

    public String username() { return username; }

    public String password() { return password; }

    public Connection open() throws Exception { return connectTo(name); }

    public DataSource dataSource() { return new DriverManagerDataSource(url(), username, password); }

    /** Drops the database, terminating anything still connected to it. */
    @Override
    public void close() {
        try (Connection admin = connectTo(ADMIN_DATABASE);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (Exception cleanupFailure) {
            // A leaked throwaway database is untidy, not a test failure worth masking a real one with.
            System.err.println("Could not drop throwaway database " + name + ": " + cleanupFailure.getMessage());
        }
    }

    private Connection connectTo(String database) throws Exception {
        return DriverManager.getConnection(urlFor(database), username, password);
    }

    private String urlFor(String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
