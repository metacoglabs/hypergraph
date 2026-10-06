// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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
