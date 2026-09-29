package com.dasannn.socialblueprint.storage;

/**
 * Runtime exception thrown when a database or storage operation fails.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
