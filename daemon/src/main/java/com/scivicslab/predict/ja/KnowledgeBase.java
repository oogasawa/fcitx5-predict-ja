package com.scivicslab.predict.ja;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * H2-backed knowledge base storing dictionary entries with time-based retention.
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
                    created_at VARCHAR NOT NULL,
                    source VARCHAR DEFAULT 'ime',
                    CONSTRAINT uq_reading_candidate UNIQUE(reading, candidate)
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_reading ON entries(reading)
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_created_at ON entries(created_at)
                """);
            // Drop legacy columns/indexes if upgrading from older schema
            try { stmt.execute("ALTER TABLE entries DROP COLUMN IF EXISTS score"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE entries DROP COLUMN IF EXISTS use_count"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE entries DROP COLUMN IF EXISTS ignore_count"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE entries DROP COLUMN IF EXISTS last_used_at"); } catch (SQLException ignored) {}
            try { stmt.execute("DROP INDEX IF EXISTS idx_score"); } catch (SQLException ignored) {}
        }
    }

    /**
     * Add an entry to the knowledge base. Duplicate reading+candidate is ignored.
     */
    public void addEntry(String reading, String candidate, String source) {
        String now = Instant.now().toString();
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO entries (reading, candidate, source, created_at)
                KEY (reading, candidate)
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
     * Delete entries older than the given cutoff.
     * Returns the number of deleted entries.
     */
    public int deleteOlderThan(String cutoffIso) {
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM entries WHERE created_at < ?
                """)) {
            ps.setString(1, cutoffIso);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOG.info("Deleted " + deleted + " entries older than " + cutoffIso);
            }
            return deleted;
        } catch (SQLException e) {
            LOG.warning("Failed to delete old entries: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Get all entries ordered by creation time (newest first).
     */
    public List<DictEntry> getAllEntries(int limit) {
        List<DictEntry> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT reading, candidate, category, created_at
                FROM entries
                ORDER BY created_at DESC
                LIMIT ?
                """)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new DictEntry(
                        rs.getString("reading"),
                        rs.getString("candidate"),
                        rs.getString("category")
                ));
            }
        } catch (SQLException e) {
            LOG.warning("Failed to get entries: " + e.getMessage());
        }
        return results;
    }

    /**
     * Prefix search: find entries whose reading starts with the given prefix.
     * Returns results ordered by creation time (newest first).
     */
    public List<DictEntry> findByPrefix(String prefix, int limit) {
        List<DictEntry> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT reading, candidate, category, created_at
                FROM entries
                WHERE reading LIKE ?
                ORDER BY created_at DESC
                LIMIT ?
                """)) {
            ps.setString(1, prefix + "%");
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new DictEntry(
                        rs.getString("reading"),
                        rs.getString("candidate"),
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

    public record DictEntry(String reading, String candidate, String category) {}
}
