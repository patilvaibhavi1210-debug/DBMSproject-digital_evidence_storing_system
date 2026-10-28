package evidence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.*;

/**
 * Run from the backend/ folder:   java -cp "out;lib/*" evidence.Main        (Windows)
 *                                 java -cp "out:lib/*" evidence.Main        (Linux / Mac)
 * Then open http://localhost:8080
 */
public class Main {

    static final int PORT = 8080;
    static final Path FRONTEND = Path.of("..", "frontend").toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        Class.forName("org.mariadb.jdbc.Driver");
        try (var c = Db.get()) {
            System.out.println("Connected to MySQL: " + c.getMetaData().getURL());
        } catch (Exception e) {
            System.out.println("!! Could not connect to MySQL. Check URL/USER/PASS in Db.java");
            System.out.println("!! " + e.getMessage());
            return;
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.start();
        System.out.println("Server running ->  http://localhost:" + PORT);
        System.out.println("Serving frontend from " + FRONTEND);
    }

    // ---------------- API ----------------
    static void handleApi(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-Token");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        String method = ex.getRequestMethod();
        if (method.equals("OPTIONS")) { send(ex, 204, "text/plain", ""); return; }

        String path = ex.getRequestURI().getPath().substring(4);   // strip "/api"
        String key = method + " " + path;

        try {
            Api.Handler h = Api.routes.get(key);
            if (h == null) { sendJson(ex, 404, error("No such endpoint: " + key)); return; }

            Map<String, Object> me = null;
            if (!Api.PUBLIC.contains(key)) {
                me = Api.sessions.get(ex.getRequestHeaders().getFirst("X-Token"));
                if (me == null) { sendJson(ex, 401, error("Please log in")); return; }
            }

            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> body = Json.parse(raw);

            sendJson(ex, 200, Json.toJson(h.handle(query, body, me)));

        } catch (SecurityException e) {
            sendJson(ex, key.equals("POST /login") ? 401 : 403, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error(e.getMessage()));
        } catch (SQLIntegrityConstraintViolationException e) {
            String m = e.getMessage() != null && e.getMessage().contains("Duplicate")
                ? "That value already exists. Case numbers, emails and tag names must be unique."
                : "This record is linked to other records. Remove those first, or pick a valid value.";
            sendJson(ex, 409, error(m));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, error("Server error: " + e.getMessage()));
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static String error(String msg) {
        return Json.toJson(Map.of("error", msg == null ? "Unknown error" : msg));
    }

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json);
    }

    static void send(HttpExchange ex, int code, String type, String text) throws IOException {
        send(ex, code, type, text.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, code == 204 ? -1 : data.length);
        if (code != 204) try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        ex.close();
    }

    // ---------------- static frontend files ----------------
    static void handleStatic(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path file = FRONTEND.resolve(p.substring(1)).normalize();

        if (!file.startsWith(FRONTEND) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".css")  ? "text/css; charset=utf-8"
                    : name.endsWith(".js")   ? "application/javascript; charset=utf-8"
                    : name.endsWith(".png")  ? "image/png"
                    : name.endsWith(".svg")  ? "image/svg+xml"
                    : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }
}
