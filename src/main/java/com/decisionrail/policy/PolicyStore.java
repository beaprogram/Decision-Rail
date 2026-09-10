package com.decisionrail.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for immutable policy versions. */
@Repository
public class PolicyStore {
    private final JdbcTemplate jdbc;

    public PolicyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a version if its identifier is unused.
     *
     * @return true when this call created the row. False means the identifier already exists,
     *         and the caller must compare hashes to decide between an idempotent retry and an
     *         attempt to rebind the name to different content.
     */
    public boolean insertIfAbsent(String versionId, String canonicalDefinition, String hash,
                                  String origin, int ruleCount, String createdBy) {
        return jdbc.update("""
                INSERT INTO policy_versions (version_id, definition, definition_hash, origin, rule_count, created_by)
                VALUES (?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (version_id) DO NOTHING
                """, versionId, canonicalDefinition, hash, origin, ruleCount, createdBy) == 1;
    }

    public PolicyVersion find(String versionId) {
        List<PolicyVersion> rows = jdbc.query("""
                SELECT version_id, definition, definition_hash, origin, rule_count, created_by, created_at
                FROM policy_versions WHERE version_id = ?
                """, PolicyStore::map, versionId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<PolicyVersion> list(int limit) {
        return jdbc.query("""
                SELECT version_id, definition, definition_hash, origin, rule_count, created_by, created_at
                FROM policy_versions ORDER BY created_at DESC, version_id LIMIT ?
                """, PolicyStore::map, limit);
    }

    private static PolicyVersion map(ResultSet rs, int row) throws SQLException {
        return new PolicyVersion(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getString(6), rs.getTimestamp(7).toInstant());
    }
}
