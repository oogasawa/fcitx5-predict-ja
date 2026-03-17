package com.scivicslab.predict.ja;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * SQLite-backed knowledge base storing all dictionary entries with scores.
 * This actor is accessed via ActorRef to serialize all writes.
 */
public class KnowledgeBase {

    private static final Logger LOG = Logger.getLogger(KnowledgeBase.class.getName());

    private final Connection conn;

    public KnowledgeBase(String dbPath) {
        try {
            conn = DriverManager.getConnection(
                    "jdbc:h2:" + dbPath + ";AUTO_SERVER=TRUE", "", "");
            conn.setAutoCommit(true);
            initSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open knowledge base: " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS entries (
                    id IDENTITY PRIMARY KEY,
                    reading VARCHAR NOT NULL,
                    candidate VARCHAR NOT NULL,
                    category VARCHAR DEFAULT 'general',
                    score DOUBLE DEFAULT 1.0,
                    use_count INT DEFAULT 0,
                    ignore_count INT DEFAULT 0,
                    created_at VARCHAR NOT NULL,
                    last_used_at VARCHAR,
                    source VARCHAR DEFAULT 'ime',
                    CONSTRAINT uq_reading_candidate UNIQUE(reading, candidate)
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_reading ON entries(reading)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_score ON entries(score DESC)
                """);
        }
    }

    /**
     * Add an entry to the knowledge base. If it already exists, bump its score.
     */
    public void addEntry(String reading, String candidate, String source) {
        String now = Instant.now().toString();
        // Try update first (bump score if exists)
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE entries SET score = score + 0.5, last_used_at = ?
                WHERE reading = ? AND candidate = ?
                """)) {
            ps.setString(1, now);
            ps.setString(2, reading);
            ps.setString(3, candidate);
            int updated = ps.executeUpdate();
            if (updated > 0) return; // Existing entry bumped
        } catch (SQLException e) {
            LOG.warning("Failed to update entry: " + e.getMessage());
        }
        // Insert new entry
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO entries (reading, candidate, source, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, reading);
            ps.setString(2, candidate);
            ps.setString(3, source);
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Failed to add entry: " + e.getMessage());
        }
    }

    /**
     * Record that a candidate was used (selected by user).
     */
    public void recordUse(String reading, String candidate) {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE entries SET
                    use_count = use_count + 1,
                    score = score + 1.0,
                    last_used_at = ?
                WHERE reading = ? AND candidate = ?
                """)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, reading);
            ps.setString(3, candidate);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Failed to record use: " + e.getMessage());
        }
    }

    /**
     * Record that a candidate was ignored (not selected).
     */
    public void recordIgnore(String reading) {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE entries SET
                    ignore_count = ignore_count + 1,
                    score = GREATEST(0, score - 0.1)
                WHERE reading = ?
                """)) {
            ps.setString(1, reading);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Failed to record ignore: " + e.getMessage());
        }
    }

    /**
     * Get top N entries by score for promotion to the active dictionary.
     */
    public List<DictEntry> getTopEntries(int limit) {
        List<DictEntry> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT reading, candidate, score, category
                FROM entries
                WHERE score > 0.5
                ORDER BY score DESC
                LIMIT ?
                """)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new DictEntry(
                        rs.getString("reading"),
                        rs.getString("candidate"),
                        rs.getDouble("score"),
                        rs.getString("category")
                ));
            }
        } catch (SQLException e) {
            LOG.warning("Failed to get top entries: " + e.getMessage());
        }
        return results;
    }

    /**
     * Prefix search: find entries whose reading starts with the given prefix.
     * Returns top N results ordered by score descending.
     */
    public List<DictEntry> findByPrefix(String prefix, int limit) {
        List<DictEntry> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT reading, candidate, score, category
                FROM entries
                WHERE reading LIKE ? AND score > 0.5
                ORDER BY score DESC
                LIMIT ?
                """)) {
            ps.setString(1, prefix + "%");
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new DictEntry(
                        rs.getString("reading"),
                        rs.getString("candidate"),
                        rs.getDouble("score"),
                        rs.getString("category")
                ));
            }
        } catch (SQLException e) {
            LOG.warning("Failed to prefix search: " + e.getMessage());
        }
        return results;
    }

    /**
     * Get total number of entries.
     */
    public int getEntryCount() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entries")) {
            if (rs.next()) return rs.getInt(1);
            return 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public void close() {
        try { conn.close(); } catch (SQLException e) { /* ignore */ }
    }

    public record DictEntry(String reading, String candidate, double score, String category) {}
}
