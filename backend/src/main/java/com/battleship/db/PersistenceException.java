package com.battleship.db;

/** Unchecked wrapper so game threads are not forced to handle SQLException. */
public class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
