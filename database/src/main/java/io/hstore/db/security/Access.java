// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class Access {

    public enum Privilege {
        READ,
        WRITE,
        MONITOR,
        MAINTAIN;

        public boolean onType() {
            return this == READ || this == WRITE;
        }
    }

    public record Grant(String user, int type, Privilege privilege) {
    }

    public record Options(String user, long validUntil, int connectionLimit) {
        public static Options defaults(String user) {
            return new Options(user, Long.MAX_VALUE, -1);
        }

        public boolean expired(long now) {
            return now >= validUntil;
        }
    }

    private static final int MONITOR_TARGET = -1;
    private static final int MAINTAIN_TARGET = -2;

    private static final ValueCodec<Grant> GRANT_CODEC = ValueCodec.rows(
            grant -> 20 + ByteCursor.stringSize(grant.user()),
            (out, grant) -> out.putString(grant.user()).putSignedVarLong(grant.type()).putByte(grant.privilege().ordinal()),
            in -> new Grant(in.getString(), (int) in.getSignedVarLong(), Privilege.values()[in.getUnsignedByte()]));

    private static final ValueCodec<Options> OPTIONS_CODEC = ValueCodec.rows(
            options -> 30 + ByteCursor.stringSize(options.user()),
            (out, options) -> out.putString(options.user()).putSignedVarLong(options.validUntil()).putSignedVarLong(options.connectionLimit()),
            in -> new Options(in.getString(), in.getSignedVarLong(), (int) in.getSignedVarLong()));

    public static final Slot<Grant> GRANTS = Slot.primary(75, "grants", new TreeSchema<>(110, "grants", FingerprintMode.SET,
            GRANT_CODEC, EntryMeasure.keyed(grant -> Hashing.of(Hashing.of(grant.user()), grant.type(), grant.privilege().ordinal()))));
    public static final Slot<Options> USER_OPTIONS = Slot.primary(76, "user-options", new TreeSchema<>(111, "user-options", FingerprintMode.SET,
            OPTIONS_CODEC, EntryMeasure.keyed(options -> Hashing.of(Hashing.of(options.user()), options.validUntil(), options.connectionLimit()))));
    public static final List<Slot<?>> SLOTS = List.of(GRANTS, USER_OPTIONS);

    private Access() {
    }

    private static int target(int type, Privilege privilege) {
        return switch (privilege) {
            case READ, WRITE -> type;
            case MONITOR -> MONITOR_TARGET;
            case MAINTAIN -> MAINTAIN_TARGET;
        };
    }

    private static long key(String user, int target) {
        return Hashing.of(Hashing.of(user), target);
    }

    private static Optional<Grant> stored(View view, String user, int target) {
        return view.get(GRANTS, key(user, target)).filter(grant -> grant.user().equals(user) && grant.type() == target);
    }

    public static Optional<Privilege> onType(View view, String user, int type) {
        return stored(view, user, type).map(Grant::privilege);
    }

    public static boolean holds(View view, String user, Privilege capability) {
        if (capability.onType()) {
            throw new IllegalArgumentException(capability + " is a privilege on a type");
        }
        return stored(view, user, target(0, capability)).isPresent();
    }

    public static boolean grant(Transaction txn, String user, int type, Privilege privilege) {
        int target = target(type, privilege);
        Optional<Grant> current = stored(txn, user, target);
        if (current.isPresent() && (current.get().privilege() == privilege || current.get().privilege() == Privilege.WRITE)) {
            return false;
        }
        requireFree(txn, user, target);
        txn.put(GRANTS, key(user, target), new Grant(user, target, privilege));
        return true;
    }

    public static boolean revoke(Transaction txn, String user, int type, Privilege privilege) {
        int target = target(type, privilege);
        Optional<Grant> current = stored(txn, user, target);
        if (current.isEmpty()) {
            return false;
        }
        if (privilege == Privilege.WRITE) {
            if (current.get().privilege() != Privilege.WRITE) {
                return false;
            }
            txn.put(GRANTS, key(user, target), new Grant(user, target, Privilege.READ));
        } else {
            txn.delete(GRANTS, key(user, target));
        }
        return true;
    }

    private static void requireFree(View view, String user, int target) {
        view.get(GRANTS, key(user, target))
                .filter(grant -> !grant.user().equals(user) || grant.type() != target)
                .ifPresent(_ -> {
                    throw HStoreException.invalid("cannot store this grant: its key collides with another grant");
                });
    }

    public static List<Grant> grants(View view) {
        return view.scan(GRANTS).values()
                .sorted(Comparator.comparing(Grant::user).thenComparingInt(Grant::type))
                .toList();
    }

    public static void forget(Transaction txn, String user) {
        grants(txn).stream().filter(grant -> grant.user().equals(user))
                .forEach(grant -> txn.delete(GRANTS, key(user, grant.type())));
        txn.delete(USER_OPTIONS, Hashing.of(user));
    }

    public static Options options(View view, String user) {
        return view.get(USER_OPTIONS, Hashing.of(user)).filter(options -> options.user().equals(user)).orElseGet(() -> Options.defaults(user));
    }

    public static void setOptions(Transaction txn, Options options) {
        if (options.equals(Options.defaults(options.user()))) {
            txn.delete(USER_OPTIONS, Hashing.of(options.user()));
        } else {
            txn.put(USER_OPTIONS, Hashing.of(options.user()), options);
        }
    }

    public static boolean isCapability(int type) {
        return type < 0;
    }
}
