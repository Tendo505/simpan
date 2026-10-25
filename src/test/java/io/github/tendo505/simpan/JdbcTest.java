package io.github.tendo505.simpan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tendo505.simpan.engine.Database;
import io.github.tendo505.simpan.server.PgServer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The wire protocol, checked with the official PostgreSQL JDBC driver as the client. */
class JdbcTest {
    @TempDir
    Path dir;
    Database db;
    PgServer server;

    @BeforeEach
    void start() throws Exception {
        db = Database.open(dir, new Database.Options(256, true, 64L << 20));
        server = new PgServer(db, null, 0);
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        db.close();
    }

    private Connection connect(boolean serverSidePrepare) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "simpan");
        // prepareThreshold=1 makes the driver use named server-side statements (and binary values) from the first run.
        p.setProperty("prepareThreshold", serverSidePrepare ? "1" : "5");
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + server.port() + "/simpan", p);
    }

    @Test
    void statementsAndPreparedStatements() throws Exception {
        for (boolean serverSide : new boolean[] {false, true}) {
            try (Connection c = connect(serverSide); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS items");
                st.execute("CREATE TABLE items (id bigint PRIMARY KEY, name text NOT NULL, price double precision, active boolean)");
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO items VALUES (?, ?, ?, ?)")) {
                    for (int i = 1; i <= 50; i++) {
                        ins.setLong(1, i);
                        ins.setString(2, "item " + i);
                        if (i % 10 == 0) ins.setNull(3, java.sql.Types.DOUBLE);
                        else ins.setDouble(3, i * 1.5);
                        ins.setBoolean(4, i % 2 == 0);
                        assertEquals(1, ins.executeUpdate());
                    }
                }
                try (PreparedStatement q = c.prepareStatement("SELECT id, name, price, active FROM items WHERE id = ?")) {
                    for (int round = 0; round < 3; round++) { // the driver switches to a named statement as it's reused
                        q.setInt(1, 7);
                        try (ResultSet rs = q.executeQuery()) {
                            assertTrue(rs.next());
                            assertEquals(7, rs.getLong(1));
                            assertEquals("item 7", rs.getString(2));
                            assertEquals(10.5, rs.getDouble(3));
                            assertFalse(rs.getBoolean(4));
                            assertFalse(rs.next());
                        }
                    }
                    q.setInt(1, 10);
                    try (ResultSet rs = q.executeQuery()) {
                        assertTrue(rs.next());
                        assertNull(rs.getObject(3));
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*), sum(price), max(name) FROM items WHERE active")) {
                    assertTrue(rs.next());
                    assertEquals(25, rs.getLong(1));
                    assertEquals("item 8", rs.getString(3));
                }
                SQLException dup = assertThrows(SQLException.class, () -> st.execute("INSERT INTO items VALUES (1, 'x', 1, true)"));
                assertEquals("23505", dup.getSQLState());
                // The connection is still usable after an error.
                try (ResultSet rs = st.executeQuery("SELECT name FROM items ORDER BY id DESC LIMIT 2")) {
                    List<String> names = new ArrayList<>();
                    while (rs.next()) names.add(rs.getString(1));
                    assertEquals(List.of("item 50", "item 49"), names);
                }
            }
        }
    }

    /** Found in review: binary parameters were decoded by guessed types, and execution re-guessed them. */
    @Test
    void declaredParameterTypesWin() throws Exception {
        try (Connection c = connect(true); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE prices (id bigint PRIMARY KEY, name text, price double precision)");
            for (int i = 1; i <= 10; i++) st.execute("INSERT INTO prices VALUES (" + i + ", 'p" + i + "', " + (i * 50) + ")");
            try (PreparedStatement del = c.prepareStatement("DELETE FROM prices WHERE price > ?")) {
                del.setLong(1, 400); // an int8 compared with a double precision column
                assertEquals(2, del.executeUpdate());
            }
            try (PreparedStatement upd = c.prepareStatement("UPDATE prices SET name = 'big' WHERE price > ?")) {
                upd.setInt(1, 300);
                assertEquals(2, upd.executeUpdate());
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO prices VALUES (?, 'bd', ?)")) {
                ins.setLong(1, 99);
                ins.setBigDecimal(2, new java.math.BigDecimal("-12.25"));
                ins.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT price FROM prices WHERE id = 99")) {
                assertTrue(rs.next());
                assertEquals(-12.25, rs.getDouble(1));
            }
            try (PreparedStatement q = c.prepareStatement("SELECT ? * 2")) {
                q.setDouble(1, 1.25);
                try (ResultSet rs = q.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2.5, rs.getDouble(1), "a declared float8 isn't rounded to an integer");
                }
            }
        }
    }

    /** Found in review: a batch, or several statements in one query, must be all or nothing, as in PostgreSQL. */
    @Test
    void batchesAreAtomic() throws Exception {
        try (Connection c = connect(false); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE m (id int PRIMARY KEY)");
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO m VALUES (?)")) {
                for (int id : new int[] {1, 2, 2, 3}) {
                    ins.setInt(1, id);
                    ins.addBatch();
                }
                assertThrows(java.sql.BatchUpdateException.class, ins::executeBatch);
            }
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO m VALUES (7); INSERT INTO m VALUES (7)"));
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM m")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong(1), "nothing from the failed batch or query was kept");
            }
        }
    }

    @Test
    void transactionsThroughTheDriver() throws Exception {
        try (Connection a = connect(true); Connection b = connect(true)) {
            try (Statement st = a.createStatement()) {
                st.execute("CREATE TABLE counter (id int PRIMARY KEY, n int)");
                st.execute("INSERT INTO counter VALUES (1, 0)");
            }
            a.setAutoCommit(false);
            try (Statement st = a.createStatement()) {
                st.executeUpdate("UPDATE counter SET n = 41 WHERE id = 1");
                try (Statement other = b.createStatement(); ResultSet rs = other.executeQuery("SELECT n FROM counter")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "an uncommitted change is invisible to others");
                }
                a.rollback();
                st.executeUpdate("UPDATE counter SET n = n + 1 WHERE id = 1");
                a.commit();
            }
            try (Statement other = b.createStatement(); ResultSet rs = other.executeQuery("SELECT n FROM counter")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            // An error inside a transaction aborts it until rollback, as in PostgreSQL.
            try (Statement st = a.createStatement()) {
                assertThrows(SQLException.class, () -> st.executeQuery("SELECT * FROM nope"));
                SQLException aborted = assertThrows(SQLException.class, () -> st.executeQuery("SELECT 1"));
                assertEquals("25P02", aborted.getSQLState());
                a.rollback();
                try (ResultSet rs = st.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                }
            }
        }
    }
}
