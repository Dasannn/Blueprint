package com.dasannn.socialblueprint.storage;

import java.sql.Statement;
import java.util.Objects;

/**
 * Test utility residing in com.dasannn.socialblueprint.storage to execute raw SQL
 * operations (such as SQLite fault-injection triggers) for integration tests without
 * widening StorageEngine's package-private visibility.
 */
public final class StorageTestSupport {

    private StorageTestSupport() {}

    public static void executeSql(StorageEngine storage, String sql) {
        Objects.requireNonNull(storage, "storage must not be null");
        Objects.requireNonNull(sql, "sql must not be null");
        storage.execute(conn -> {
            try (Statement s = conn.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    public static void setFailReputationTrigger(StorageEngine storage) {
        executeSql(storage, "CREATE TRIGGER fail_rep BEFORE INSERT ON reputation_event BEGIN SELECT RAISE(FAIL, 'simulated reputation write failure'); END;");
    }

    public static void dropFailReputationTrigger(StorageEngine storage) {
        executeSql(storage, "DROP TRIGGER IF EXISTS fail_rep;");
    }

    public static void setFailAuditTrigger(StorageEngine storage) {
        executeSql(storage, "CREATE TRIGGER fail_audit BEFORE INSERT ON audit_event BEGIN SELECT RAISE(FAIL, 'simulated audit failure'); END;");
    }

    public static void dropFailAuditTrigger(StorageEngine storage) {
        executeSql(storage, "DROP TRIGGER IF EXISTS fail_audit;");
    }

    public static void setFailCompensationInsertTrigger(StorageEngine storage) {
        executeSql(storage, "CREATE TRIGGER fail_comp_insert BEFORE INSERT ON pending_compensation BEGIN SELECT RAISE(FAIL, 'simulated compensation insert failure'); END;");
    }

    public static void dropFailCompensationInsertTrigger(StorageEngine storage) {
        executeSql(storage, "DROP TRIGGER IF EXISTS fail_comp_insert;");
    }

    public static void setFailCompensationDeleteTrigger(StorageEngine storage) {
        executeSql(storage, "CREATE TRIGGER fail_comp_delete BEFORE DELETE ON pending_compensation BEGIN SELECT RAISE(FAIL, 'simulated compensation delete failure'); END;");
    }

    public static void dropFailCompensationDeleteTrigger(StorageEngine storage) {
        executeSql(storage, "DROP TRIGGER IF EXISTS fail_comp_delete;");
    }
}
