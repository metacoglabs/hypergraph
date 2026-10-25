// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

public enum Role {
    ADMIN,
    WRITER,
    READER,
    NONE;

    public boolean canWrite() {
        return this != READER;
    }

    public boolean canAdminister() {
        return this == ADMIN;
    }

    public boolean readsEveryType() {
        return this != NONE;
    }

    public boolean writesEveryType() {
        return this == ADMIN || this == WRITER;
    }
}
