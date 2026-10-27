package evidence;

import java.sql.*;
import java.util.*;

/** Small JDBC helper: every query uses PreparedStatement (prevents SQL injection). */
public class Db {

    // ====== CHANGE THESE THREE LINES FOR YOUR MYSQL SETUP ======
    static final String URL = System.getenv().getOrDefault(
    "DB_URL",
    "jdbc:mysql://localhost:3306/project_dems?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata"
);

static final String USER = System.getenv().getOrDefault(
    "DB_USER", "evidence_app"
);

static final String PASS = System.getenv("DB_PASSWORD");
    // ===========================================================

    public interface Tx { void run(Connection c) throws Exception; }

    static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    // ---------- SELECT ----------
    static List<Map<String, Object>> query(String sql, Object... p) throws SQLException {
        try (Connection c = get()) { return query(c, sql, p); }
    }

    static List<Map<String, Object>> query(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    // ---------- INSERT / UPDATE / DELETE (returns generated key, or 0) ----------
    static long update(String sql, Object... p) throws SQLException {
        try (Connection c = get()) { return update(c, sql, p); }
    }

    static long update(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, p);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : 0;
            }
        }
    }

    // ---------- Transaction: all statements succeed or none do ----------
    static void tx(Tx work) throws Exception {
        try (Connection c = get()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
    }
}
