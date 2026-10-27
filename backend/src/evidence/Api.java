package evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Every REST endpoint. Key format: "METHOD /path" (the /api prefix is removed by Main). */
public class Api {

    /** query = URL params, body = JSON body, me = logged-in user row. */
    interface Handler {
        Object handle(Map<String, String> query, Map<String, String> body, Map<String, Object> me) throws Exception;
    }

    static final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();
    static final Map<String, Handler> routes = new HashMap<>();
    /** Routes that work without a token. */
    static final Set<String> PUBLIC = Set.of("POST /login");

    // ---------- helpers ----------
    static String sha256(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Optional value: blank becomes null (so MySQL stores NULL). */
    static String opt(Map<String, String> m, String k) {
        String v = m.get(k);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** Required value: throws a friendly error when missing. */
    static String req(Map<String, String> m, String k) {
        String v = opt(m, k);
        if (v == null) throw new IllegalArgumentException("Missing field: " + k);
        return v;
    }

    static Map<String, Object> ok(Object id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", id);
        return r;
    }

    static int myId(Map<String, Object> me) {
        return ((Number) me.get("user_id")).intValue();
    }

    // ---------- route table ----------
    static {
        // ===== AUTH =====
        routes.put("POST /login", (q, b, me) -> {
            List<Map<String, Object>> u = Db.query(
                "SELECT user_id, name, email, role FROM users WHERE email=? AND password_hash=?",
                req(b, "email"), sha256(req(b, "password")));
            if (u.isEmpty()) throw new SecurityException("Wrong email or password");
            String token = UUID.randomUUID().toString();
            sessions.put(token, u.get(0));
            Map<String, Object> r = new LinkedHashMap<>(u.get(0));
            r.put("token", token);
            return r;
        });

        // ===== DASHBOARD =====
        routes.put("GET /stats", (q, b, me) -> Db.query(
            "SELECT (SELECT COUNT(*) FROM cases) AS cases, " +
            "(SELECT COUNT(*) FROM cases WHERE status <> 'Closed') AS open_cases, " +
            "(SELECT COUNT(*) FROM evidence) AS evidence, " +
            "(SELECT COUNT(*) FROM evidence WHERE status='In Storage') AS in_storage, " +
            "(SELECT COUNT(*) FROM evidence WHERE status IN ('Checked Out','In Lab','In Court')) AS out_of_storage, " +
            "(SELECT COUNT(*) FROM storage_location) AS locations, " +
            "(SELECT COUNT(*) FROM users) AS users").get(0));

        routes.put("GET /recent", (q, b, me) -> Db.query(
            "SELECT c.custody_id, c.action_time, c.action_type, c.remarks, c.evidence_id, e.item_type, " +
            "fu.name AS from_name, tu.name AS to_name " +
            "FROM chain_of_custody c JOIN evidence e ON e.evidence_id=c.evidence_id " +
            "LEFT JOIN users fu ON fu.user_id=c.from_user_id LEFT JOIN users tu ON tu.user_id=c.to_user_id " +
            "ORDER BY c.action_time DESC, c.custody_id DESC LIMIT 8"));

        // ===== USERS =====
        routes.put("GET /users", (q, b, me) -> Db.query(
            "SELECT user_id, name, email, role, phone, designation FROM users ORDER BY name"));

        routes.put("POST /users", (q, b, me) -> {
            if (!"Admin".equals(me.get("role"))) throw new SecurityException("Only an Admin can add users");
            long id = Db.update(
                "INSERT INTO users (name,email,password_hash,role,phone,designation) VALUES (?,?,?,?,?,?)",
                req(b, "name"), req(b, "email"), sha256(req(b, "password")),
                opt(b, "role") == null ? "Officer" : opt(b, "role"), opt(b, "phone"), opt(b, "designation"));
            return ok(id);
        });

        // ===== CASES =====
        routes.put("GET /cases", (q, b, me) -> {
            String s = opt(q, "q");
            String like = s == null ? "%" : "%" + s + "%";
            return Db.query(
                "SELECT c.*, u.name AS created_by_name, " +
                "(SELECT COUNT(*) FROM evidence e WHERE e.case_id=c.case_id) AS evidence_count " +
                "FROM cases c LEFT JOIN users u ON u.user_id=c.created_by " +
                "WHERE c.title LIKE ? OR c.case_number LIKE ? ORDER BY c.case_id DESC", like, like);
        });

        routes.put("POST /cases", (q, b, me) -> {
            long id = Db.update(
                "INSERT INTO cases (case_number,title,description,reported_date,status,created_by) VALUES (?,?,?,?,?,?)",
                req(b, "case_number"), req(b, "title"), opt(b, "description"), req(b, "reported_date"),
                opt(b, "status") == null ? "Open" : opt(b, "status"), myId(me));
            // the creator is automatically the lead on the case
            Db.update("INSERT INTO case_assignment (case_id,user_id,assigned_role) VALUES (?,?,'Lead')", id, myId(me));
            return ok(id);
        });

        routes.put("PUT /cases", (q, b, me) -> {
            Db.update("UPDATE cases SET status=? WHERE case_id=?", req(b, "status"), req(b, "id"));
            return ok(b.get("id"));
        });

        routes.put("DELETE /cases", (q, b, me) -> {
            Db.update("DELETE FROM cases WHERE case_id=?", req(q, "id"));
            return ok(q.get("id"));
        });

        // ===== CASE ASSIGNMENT (M:N USER-CASE) =====
        routes.put("GET /assignments", (q, b, me) -> Db.query(
            "SELECT a.case_id, a.user_id, u.name, u.designation, a.assigned_role, a.assigned_at " +
            "FROM case_assignment a JOIN users u ON u.user_id=a.user_id WHERE a.case_id=? ORDER BY a.assigned_at",
            req(q, "case_id")));

        routes.put("POST /assignments", (q, b, me) -> {
            Db.update("INSERT INTO case_assignment (case_id,user_id,assigned_role) VALUES (?,?,?) " +
                      "ON DUPLICATE KEY UPDATE assigned_role=VALUES(assigned_role)",
                req(b, "case_id"), req(b, "user_id"), opt(b, "assigned_role") == null ? "Investigator" : opt(b, "assigned_role"));
            return ok(null);
        });

        routes.put("DELETE /assignments", (q, b, me) -> {
            Db.update("DELETE FROM case_assignment WHERE case_id=? AND user_id=?", req(q, "case_id"), req(q, "user_id"));
            return ok(null);
        });

        // ===== CASE NOTES =====
        routes.put("GET /notes", (q, b, me) -> Db.query(
            "SELECT n.note_id, n.note_text, n.created_at, u.name AS author " +
            "FROM case_note n LEFT JOIN users u ON u.user_id=n.author_id WHERE n.case_id=? ORDER BY n.created_at DESC",
            req(q, "case_id")));

        routes.put("POST /notes", (q, b, me) ->
            ok(Db.update("INSERT INTO case_note (case_id,author_id,note_text) VALUES (?,?,?)",
                req(b, "case_id"), myId(me), req(b, "note_text"))));

        // ===== STORAGE LOCATIONS =====
        routes.put("GET /locations", (q, b, me) -> Db.query(
            "SELECT l.*, (SELECT COUNT(*) FROM evidence e WHERE e.location_id=l.location_id) AS items_stored " +
            "FROM storage_location l ORDER BY room_number, cabinet_number, shelf_number"));

        routes.put("POST /locations", (q, b, me) ->
            ok(Db.update("INSERT INTO storage_location (room_number,cabinet_number,shelf_number,locker_number) VALUES (?,?,?,?)",
                req(b, "room_number"), opt(b, "cabinet_number"), opt(b, "shelf_number"), opt(b, "locker_number"))));

        routes.put("DELETE /locations", (q, b, me) -> {
            Db.update("DELETE FROM storage_location WHERE location_id=?", req(q, "id"));
            return ok(null);
        });

        // ===== TAGS =====
        routes.put("GET /tags", (q, b, me) -> Db.query(
            "SELECT t.*, (SELECT COUNT(*) FROM evidence_tag et WHERE et.tag_id=t.tag_id) AS used_on " +
            "FROM tag t ORDER BY tag_name"));

        routes.put("POST /tags", (q, b, me) ->
            ok(Db.update("INSERT INTO tag (tag_name,description) VALUES (?,?)", req(b, "tag_name"), opt(b, "description"))));

        routes.put("GET /evidence/tags", (q, b, me) -> Db.query(
            "SELECT t.tag_id, t.tag_name, et.added_at FROM evidence_tag et JOIN tag t ON t.tag_id=et.tag_id " +
            "WHERE et.evidence_id=? ORDER BY t.tag_name", req(q, "evidence_id")));

        routes.put("POST /evidence/tag", (q, b, me) -> {
            Db.update("INSERT IGNORE INTO evidence_tag (evidence_id,tag_id) VALUES (?,?)", req(b, "evidence_id"), req(b, "tag_id"));
            return ok(null);
        });

        routes.put("DELETE /evidence/tag", (q, b, me) -> {
            Db.update("DELETE FROM evidence_tag WHERE evidence_id=? AND tag_id=?", req(q, "evidence_id"), req(q, "tag_id"));
            return ok(null);
        });

        // ===== EVIDENCE =====
        routes.put("GET /evidence", (q, b, me) -> {
            StringBuilder sql = new StringBuilder(
                "SELECT e.*, c.case_number, c.title AS case_title, " +
                "CONCAT_WS(' / ', l.room_number, l.cabinet_number, l.shelf_number, l.locker_number) AS location_label, " +
                "(SELECT GROUP_CONCAT(t.tag_name SEPARATOR ', ') FROM evidence_tag et JOIN tag t ON t.tag_id=et.tag_id " +
                "  WHERE et.evidence_id=e.evidence_id) AS tags " +
                "FROM evidence e JOIN cases c ON c.case_id=e.case_id " +
                "LEFT JOIN storage_location l ON l.location_id=e.location_id WHERE 1=1");
            List<Object> p = new ArrayList<>();
            if (opt(q, "case_id") != null) { sql.append(" AND e.case_id=?"); p.add(q.get("case_id")); }
            if (opt(q, "status") != null)  { sql.append(" AND e.status=?");  p.add(q.get("status")); }
            if (opt(q, "q") != null) {
                sql.append(" AND (e.item_type LIKE ? OR e.description LIKE ? OR c.case_number LIKE ?)");
                String like = "%" + q.get("q") + "%";
                p.add(like); p.add(like); p.add(like);
            }
            sql.append(" ORDER BY e.evidence_id DESC");
            return Db.query(sql.toString(), p.toArray());
        });

        routes.put("POST /evidence", (q, b, me) -> {
            long[] id = new long[1];
            // evidence row + first chain-of-custody entry are saved together
            Db.tx(c -> {
                id[0] = Db.update(c,
                    "INSERT INTO evidence (case_id,item_type,description,seized_date,item_condition,photo_path,status,location_id) " +
                    "VALUES (?,?,?,?,?,?, 'In Storage', ?)",
                    req(b, "case_id"), req(b, "item_type"), opt(b, "description"), req(b, "seized_date"),
                    opt(b, "item_condition"), opt(b, "photo_path"), opt(b, "location_id"));
                Db.update(c, "INSERT INTO chain_of_custody (evidence_id,from_user_id,to_user_id,action_type,remarks) " +
                             "VALUES (?,NULL,?, 'Collected', 'Evidence registered in the system')", id[0], myId(me));
            });
            return ok(id[0]);
        });

        routes.put("PUT /evidence", (q, b, me) -> {
            Db.update("UPDATE evidence SET status=? WHERE evidence_id=?", req(b, "status"), req(b, "id"));
            return ok(b.get("id"));
        });

        routes.put("DELETE /evidence", (q, b, me) -> {
            Db.update("DELETE FROM evidence WHERE evidence_id=?", req(q, "id"));
            return ok(null);
        });

        // ===== MOVE EVIDENCE: movement row + evidence update + custody row in ONE transaction =====
        routes.put("POST /evidence/move", (q, b, me) -> {
            Db.tx(c -> {
                String evId = req(b, "evidence_id");
                String type = req(b, "movement_type");
                List<Map<String, Object>> cur = Db.query(c, "SELECT location_id FROM evidence WHERE evidence_id=? FOR UPDATE", evId);
                if (cur.isEmpty()) throw new IllegalArgumentException("Evidence not found");
                Object fromLoc = cur.get(0).get("location_id");
                String toLoc = opt(b, "to_location_id");
                String toUser = opt(b, "to_user_id");

                Db.update(c, "INSERT INTO evidence_movement (evidence_id,from_location_id,to_location_id,from_user_id,to_user_id,movement_type,reason) " +
                             "VALUES (?,?,?,?,?,?,?)", evId, fromLoc, toLoc, myId(me), toUser, type, opt(b, "reason"));

                String status;
                switch (type) {
                    case "Check-out":        status = "Checked Out"; break;
                    case "Lab Submission":   status = "In Lab";      break;
                    case "Court Submission": status = "In Court";    break;
                    default:                 status = "In Storage";
                }
                // only change the stored location when a new one was chosen
                Db.update(c, "UPDATE evidence SET status=?, location_id=COALESCE(?, location_id) WHERE evidence_id=?", status, toLoc, evId);

                Db.update(c, "INSERT INTO chain_of_custody (evidence_id,from_user_id,to_user_id,action_type,remarks) VALUES (?,?,?,?,?)",
                          evId, myId(me), toUser, type, opt(b, "reason"));
            });
            return ok(null);
        });

        routes.put("GET /movements", (q, b, me) -> Db.query(
            "SELECT m.movement_id, m.movement_type, m.movement_date, m.reason, " +
            "CONCAT_WS(' / ', fl.room_number, fl.cabinet_number, fl.shelf_number) AS from_location, " +
            "CONCAT_WS(' / ', tl.room_number, tl.cabinet_number, tl.shelf_number) AS to_location, " +
            "fu.name AS from_user, tu.name AS to_user " +
            "FROM evidence_movement m " +
            "LEFT JOIN storage_location fl ON fl.location_id=m.from_location_id " +
            "LEFT JOIN storage_location tl ON tl.location_id=m.to_location_id " +
            "LEFT JOIN users fu ON fu.user_id=m.from_user_id LEFT JOIN users tu ON tu.user_id=m.to_user_id " +
            "WHERE m.evidence_id=? ORDER BY m.movement_date DESC, m.movement_id DESC", req(q, "evidence_id")));

        routes.put("GET /custody", (q, b, me) -> Db.query(
            "SELECT c.custody_id, c.action_type, c.action_time, c.remarks, fu.name AS from_name, tu.name AS to_name " +
            "FROM chain_of_custody c LEFT JOIN users fu ON fu.user_id=c.from_user_id LEFT JOIN users tu ON tu.user_id=c.to_user_id " +
            "WHERE c.evidence_id=? ORDER BY c.action_time DESC, c.custody_id DESC", req(q, "evidence_id")));
    }
}
