package io.hstore.db.security;

public enum Role {
    ADMIN,
    WRITER,
    READER;

    public boolean canWrite() {
        return this != READER;
    }

    public boolean canAdminister() {
        return this == ADMIN;
    }
}
