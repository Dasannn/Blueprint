package com.dasannn.socialblueprint.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Contract for a numbered SQLite migration per T-017 and ARCHITECTURE.md §4.
 */
public interface Migration {

    /**
     * Unique, strictly positive version number.
     */
    int version();

    /**
     * Human-readable description of what this migration applies.
     */
    String description();

    /**
     * Applies this migration to the given SQLite connection.
     */
    void apply(Connection conn) throws SQLException;
}
