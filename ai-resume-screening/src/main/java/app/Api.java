package app;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class Api implements HttpHandler {
    static final Path UPLOADS = Paths.get("uploads");
    static final int MAX_BYTES = 5 * 1024 * 1024;

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            switch (route) {
                case "POST /api/register" -> register(ex);
                case "POST /api/login" -> login(ex);
                case "POST /api/logout" -> logout(ex);
                case "GET /api/me" -> me(ex);
                case "POST /api/profile" -> saveProfile(ex);
                case "GET /api/jobs" -> listJobs(ex);
                case "POST /api/apply" -> apply(ex);
                case "GET /api/my-applications" -> myApplications(ex);
                case "GET /api/admin/jobs" -> adminJobs(ex);
                case "POST /api/admin/jobs" -> createJob(ex);
                case "DELETE /api/admin/jobs" -> deleteJob(ex);
                case "GET /api/admin/applicants" -> applicants(ex);
                case "POST /api/admin/status" -> setStatus(ex);
                case "GET /api/admin/resume" -> download(ex);
                default -> Http.send(ex, 404, err("Not found"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            try { Http.send(ex, 500, err("Server error")); } catch (Exception ignored) {}
        }
    }

    // ---------- helpers ----------
    static Map<String, String> err(String m) { return Map.of("error", m); }

    static JsonObject json(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readNBytes(100_000), StandardCharsets.UTF_8);
        try { return JsonParser.parseString(s).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : "";
    }

    static Http.Session need(HttpExchange ex, String role) throws IOException {
        Http.Session s = Http.session(ex);
        if (s == null) { Http.send(ex, 401, err("Please login")); return null; }
        if (role != null && !role.equals(s.role())) { Http.send(ex, 403, err("Forbidden")); return null; }
        return s;
    }

    static String userSkills(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT skills FROM users WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
            }
        }
    }

    // ---------- auth ----------
    static void register(HttpExchange ex) throws Exception {
        JsonObject b = json(ex);
        String name = str(b, "name"), email = str(b, "email").toLowerCase();
        String pw = str(b, "password"), skills = str(b, "skills");
        if (name.isEmpty() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || pw.length() < 6) {
            Http.send(ex, 400, err("Enter a name, a valid email and a password (min 6 characters)"));
            return;
        }
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(name,email,password_hash,role,skills) VALUES(?,?,?,'USER',?)")) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, BCrypt.hashpw(pw, BCrypt.gensalt()));
            ps.setString(4, skills);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 23505) { Http.send(ex, 409, err("Email already registered")); return; }
            throw e;
        }
        Http.send(ex, 200, Map.of("ok", true));
    }

    static void login(HttpExchange ex) throws Exception {
        JsonObject b = json(ex);
        String email = str(b, "email").toLowerCase(), pw = str(b, "password");
        long id; String name, role;
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT id,name,password_hash,role FROM users WHERE email=?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !BCrypt.checkpw(pw, rs.getString("password_hash"))) {
                    Http.send(ex, 401, err("Invalid email or password"));
                    return;
                }
                id = rs.getLong("id"); name = rs.getString("name"); role = rs.getString("role");
            }
        }
        String sid = UUID.randomUUID().toString();
        Http.sessions.put(sid, new Http.Session(id, role, name));
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + sid + "; Path=/; HttpOnly; SameSite=Lax");
        Http.send(ex, 200, Map.of("role", role, "name", name));
    }

    static void logout(HttpExchange ex) throws Exception {
        String sid = Http.sid(ex);
        if (sid != null) Http.sessions.remove(sid);
        Http.send(ex, 200, Map.of("ok", true));
    }

    static void me(HttpExchange ex) throws Exception {
        Http.Session s = need(ex, null);
        if (s == null) return;
        try (Connection c = Db.get()) {
            Http.send(ex, 200, Map.of("name", s.name(), "role", s.role(), "skills", userSkills(c, s.userId())));
        }
    }

    static void saveProfile(HttpExchange ex) throws Exception {
        Http.Session s = need(ex, "USER");
        if (s == null) return;
        String skills = str(json(ex), "skills");
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE users SET skills=? WHERE id=?")) {
            ps.setString(1, skills);
            ps.setLong(2, s.userId());
            ps.executeUpdate();
        }
        Http.send(ex, 200, Map.of("ok", true));
    }

    // ---------- user: jobs and apply ----------
    static void listJobs(HttpExchange ex) throws Exception {
        Http.Session s = need(ex, "USER");
        if (s == null) return;
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = Db.get()) {
            Set<String> mine = Scorer.skillSet(userSkills(c, s.userId()));
            Set<Long> applied = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT job_id FROM applications WHERE user_id=?")) {
                ps.setLong(1, s.userId());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) applied.add(rs.getLong(1)); }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM jobs ORDER BY id DESC")) {
                while (rs.next()) {
                    Map<String, Object> j = new LinkedHashMap<>();
                    long id = rs.getLong("id");
                    String req = rs.getString("required_skills");
                    j.put("id", id);
                    j.put("title", rs.getString("title"));
                    j.put("description", rs.getString("description"));
                    j.put("requiredSkills", req);
                    j.put("minExperience", rs.getInt("min_experience"));
                    j.put("match", Scorer.skillMatchPercent(mine, Scorer.skillSet(req)));
                    j.put("applied", applied.contains(id));
                    out.add(j);
                }
            }
        }
        out.sort((a, b) -> Double.compare((Double) b.get("match"), (Double) a.get("match")));
        Http.send(ex, 200, out);
    }

    static void apply(HttpExchange ex) throws Exception {
        Http.Session s = need(ex, "USER");
        if (s == null) return;
        long jobId;
        try { jobId = Long.parseLong(Http.query(ex).getOrDefault("jobId", "0")); } catch (Exception e) { jobId = 0; }
        String fname = URLDecoder.decode(Optional.ofNullable(ex.getRequestHeaders().getFirst("X-Filename")).orElse(""),
                StandardCharsets.UTF_8).toLowerCase();
        String ext = fname.contains(".") ? fname.substring(fname.lastIndexOf('.') + 1) : "";
        if (!List.of("pdf", "docx", "txt").contains(ext)) {
            Http.send(ex, 400, err("Upload a PDF, DOCX or TXT resume"));
            return;
        }
        byte[] data = ex.getRequestBody().readNBytes(MAX_BYTES + 1);
        if (data.length == 0 || data.length > MAX_BYTES) {
            Http.send(ex, 400, err("Resume must be between 1 byte and 5 MB"));
            return;
        }
        try (Connection c = Db.get()) {
            String title, desc, req; int minExp;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT title,description,required_skills,min_experience FROM jobs WHERE id=?")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { Http.send(ex, 404, err("Job not found")); return; }
                    title = rs.getString(1); desc = rs.getString(2); req = rs.getString(3); minExp = rs.getInt(4);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM applications WHERE job_id=? AND user_id=?")) {
                ps.setLong(1, jobId);
                ps.setLong(2, s.userId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) { Http.send(ex, 409, err("You already applied to this job")); return; }
                }
            }
            String text;
            try { text = ResumeParser.extract(data, ext); }
            catch (Exception e) { Http.send(ex, 400, err("Could not read this file")); return; }
            if (text == null || text.trim().isEmpty()) {
                Http.send(ex, 400, err("No text found in resume (scanned PDFs are not supported)"));
                return;
            }
            Scorer.Result r = Scorer.score(text, title, desc, req, minExp);
            Files.createDirectories(UPLOADS);
            String stored = UUID.randomUUID() + "." + ext;
            Files.write(UPLOADS.resolve(stored), data);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO applications(job_id,user_id,resume_file,score,breakdown) VALUES(?,?,?,?,?)")) {
                ps.setLong(1, jobId);
                ps.setLong(2, s.userId());
                ps.setString(3, stored);
                ps.setDouble(4, r.score());
                ps.setString(5, Http.gson.toJson(r.details()));
                ps.executeUpdate();
            }
        }
        Http.send(ex, 200, Map.of("ok", true));
    }

    static void myApplications(HttpExchange ex) throws Exception {
        Http.Session s = need(ex, "USER");
        if (s == null) return;
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT j.title, a.status, a.applied_at FROM applications a JOIN jobs j ON j.id=a.job_id " +
                "WHERE a.user_id=? ORDER BY a.id DESC")) {
            ps.setLong(1, s.userId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", rs.getString(1));
                    m.put("status", rs.getString(2));
                    m.put("appliedAt", rs.getString(3).substring(0, 16));
                    out.add(m);
                }
            }
        }
        Http.send(ex, 200, out);
    }

    // ---------- admin ----------
    static void createJob(HttpExchange ex) throws Exception {
        if (need(ex, "ADMIN") == null) return;
        JsonObject b = json(ex);
        String title = str(b, "title"), desc = str(b, "description"), skills = str(b, "requiredSkills");
        int minExp;
        try { minExp = Integer.parseInt(str(b, "minExperience")); } catch (Exception e) { minExp = 0; }
        if (title.isEmpty() || desc.isEmpty() || skills.isEmpty()) {
            Http.send(ex, 400, err("Title, description and required skills are required"));
            return;
        }
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO jobs(title,description,required_skills,min_experience) VALUES(?,?,?,?)")) {
            ps.setString(1, title);
            ps.setString(2, desc);
            ps.setString(3, skills);
            ps.setInt(4, Math.max(0, minExp));
            ps.executeUpdate();
        }
        Http.send(ex, 200, Map.of("ok", true));
    }

    static void adminJobs(HttpExchange ex) throws Exception {
        if (need(ex, "ADMIN") == null) return;
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = Db.get(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT j.id,j.title,j.required_skills,j.min_experience," +
                "(SELECT COUNT(*) FROM applications a WHERE a.job_id=j.id) FROM jobs j ORDER BY j.id DESC")) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong(1));
                m.put("title", rs.getString(2));
                m.put("requiredSkills", rs.getString(3));
                m.put("minExperience", rs.getInt(4));
                m.put("applicants", rs.getInt(5));
                out.add(m);
            }
        }
        Http.send(ex, 200, out);
    }

    static void deleteJob(HttpExchange ex) throws Exception {
        if (need(ex, "ADMIN") == null) return;
        long id = Long.parseLong(Http.query(ex).getOrDefault("id", "0"));
        try (Connection c = Db.get()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM applications WHERE job_id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM jobs WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
        Http.send(ex, 200, Map.of("ok", true));
    }

    static void applicants(HttpExchange ex) throws Exception {
        if (need(ex, "ADMIN") == null) return;
        long jobId = Long.parseLong(Http.query(ex).getOrDefault("jobId", "0"));
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(
                "SELECT a.id,a.score,a.breakdown,a.status,a.applied_at,u.name,u.email " +
                "FROM applications a JOIN users u ON u.id=a.user_id WHERE a.job_id=? ORDER BY a.score DESC")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong(1));
                    m.put("score", rs.getDouble(2));
                    m.put("breakdown", JsonParser.parseString(rs.getString(3)));
                    m.put("status", rs.getString(4));
                    m.put("appliedAt", rs.getString(5).substring(0, 16));
                    m.put("name", rs.getString(6));
                    m.put("email", rs.getString(7));
                    out.add(m);
                }
            }
        }
        Http.send(ex, 200, out);
    }

    static void setStatus(HttpExchange ex) throws Exception {
        if (need(ex, "ADMIN") == null) return;
        JsonObject b = json(ex);
        String status = str(b, "status");
        if (!List.of("APPLIED", "SHORTLISTED", "REJECTED").contains(status)) {
            Http.send(ex, 400, err("Invalid status"));
            return;
        }
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("UPDATE applications SET status=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setLong(2, Long.parseLong(str(b, "id")));
            ps.executeUpdate();
        }
        Http.send(ex, 200, Map.of("ok", true));
    }

    static void download(HttpExchange ex) throws Exception {
        if (need(ex, "ADMIN") == null) return;
        long id = Long.parseLong(Http.query(ex).getOrDefault("id", "0"));
        String file = null;
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT resume_file FROM applications WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) file = rs.getString(1); }
        }
        if (file == null) { Http.send(ex, 404, err("Not found")); return; }
        Path p = UPLOADS.resolve(file).normalize();
        if (!p.startsWith(UPLOADS) || !Files.exists(p)) { Http.send(ex, 404, err("File missing")); return; }
        byte[] data = Files.readAllBytes(p);
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"resume-" + id + file.substring(file.lastIndexOf('.')) + "\"");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(data); }
    }
}
