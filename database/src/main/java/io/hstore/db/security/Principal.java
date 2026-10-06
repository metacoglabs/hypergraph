// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.HStoreException;

public record Principal(String user, int tenant, Role role) {

    public static final int DEFAULT_TENANT = 0;
    public static final Principal SYSTEM = new Principal("system", DEFAULT_TENANT, Role.ADMIN);

    public Principal inTenant(int other) {
        if (!role.canAdminister() && other != tenant) {
            throw HStoreException.invalid("user " + user + " may not switch tenants");
        }
        return new Principal(user, other, role);
    }

    public void requireWrite() {
        if (!role.canWrite()) {
            throw HStoreException.invalid("user " + user + " has read-only access");
        }
    }

    public void requireAdmin() {
        if (!role.canAdminister()) {
            throw HStoreException.invalid("user " + user + " lacks the ADMIN role required for this statement");
        }
    }
}
