package app;

import org.mindrot.jbcrypt.BCrypt;
import java.sql.*;

public class Db {
    private static final String URL = "jdbc:h2:./data/jobportal";

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, "sa", "");
    }

    public static void init() throws SQLException {
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS users(" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100), email VARCHAR(150) UNIQUE, " +
                    "password_hash VARCHAR(100), role VARCHAR(10), skills VARCHAR(1000) DEFAULT '')");
            s.execute("CREATE TABLE IF NOT EXISTS jobs(" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(150), description VARCHAR(5000), " +
                    "required_skills VARCHAR(1000), min_experience INT DEFAULT 0, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS applications(" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, job_id BIGINT, user_id BIGINT, resume_file VARCHAR(255), " +
                    "score DOUBLE, breakdown VARCHAR(4000), status VARCHAR(20) DEFAULT 'APPLIED', " +
                    "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(job_id, user_id))");

            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM users WHERE role='ADMIN'")) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO users(name,email,password_hash,role,skills) VALUES(?,?,?,'ADMIN','')")) {
                        ps.setString(1, "Admin");
                        ps.setString(2, "admin@example.com");
                        ps.setString(3, BCrypt.hashpw("admin123", BCrypt.gensalt()));
                        ps.executeUpdate();
                        System.out.println("Default admin created: admin@example.com / admin123 (change it!)");
                    }
                }
            }
        }
    }
}
