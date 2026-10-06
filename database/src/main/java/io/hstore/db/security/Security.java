// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.db.security.Quota.Usage;
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

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Security {

    public record Tenant(int id, String name, Quota quota) {
    }

    public record User(String name, String salt, String hash, int iterations, int tenant, Role role) {
    }

    private static final int ITERATIONS = 120_000;
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ValueCodec<Tenant> TENANT_CODEC = ValueCodec.rows(
            tenant -> 60 + ByteCursor.stringSize(tenant.name()),
            (out, tenant) -> out.putVarInt(tenant.id()).putString(tenant.name())
                    .putVarLong(tenant.quota().maxAtoms()).putVarLong(tenant.quota().maxEdges())
                    .putVarLong(tenant.quota().maxPayloadBytes()).putVarLong(tenant.quota().maxTransactionPages())
                    .putVarLong(tenant.quota().maxQueryPages()),
            in -> new Tenant(in.getVarInt(), in.getString(),
                    new Quota(in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarLong())));

    private static final ValueCodec<User> USER_CODEC = ValueCodec.rows(
            user -> 30 + ByteCursor.stringSize(user.name()) + ByteCursor.stringSize(user.salt()) + ByteCursor.stringSize(user.hash()),
            (out, user) -> out.putString(user.name()).putString(user.salt()).putString(user.hash())
                    .putVarInt(user.iterations()).putVarInt(user.tenant()).putByte(user.role().ordinal()),
            in -> new User(in.getString(), in.getString(), in.getString(), in.getVarInt(), in.getVarInt(), Role.values()[in.getUnsignedByte()]));

    private static final ValueCodec<Usage> USAGE_CODEC = ValueCodec.rows(
            _ -> 30,
            (out, usage) -> out.putSignedVarLong(usage.atoms()).putSignedVarLong(usage.edges()).putSignedVarLong(usage.payloadBytes()),
            in -> new Usage(in.getSignedVarLong(), in.getSignedVarLong(), in.getSignedVarLong()));

    public static final Slot<Tenant> TENANTS = Slot.primary(44, "tenants", new TreeSchema<>(81, "tenants", FingerprintMode.SET,
            TENANT_CODEC, EntryMeasure.keyed(tenant -> Hashing.of(tenant.id(), Hashing.of(tenant.name()), tenant.quota().maxAtoms(), tenant.quota().maxEdges(),
                    tenant.quota().maxPayloadBytes(), tenant.quota().maxTransactionPages(), tenant.quota().maxQueryPages()))));

    public static final Slot<User> USERS = Slot.primary(45, "users", new TreeSchema<>(82, "users", FingerprintMode.SET,
            USER_CODEC, EntryMeasure.keyed(user -> Hashing.of(Hashing.of(user.name()), Hashing.of(user.hash()), user.tenant(), user.role().ordinal()))));

    public static final Slot<Usage> USAGE = Slot.primary(46, "tenant-usage", new TreeSchema<>(83, "tenant-usage", FingerprintMode.SET,
            USAGE_CODEC, EntryMeasure.keyed(usage -> Hashing.of(usage.atoms(), usage.edges(), usage.payloadBytes()))));

    public static final List<Slot<?>> SLOTS = List.of(TENANTS, USERS, USAGE);

    public static final Tenant DEFAULT = new Tenant(Principal.DEFAULT_TENANT, "default", Quota.UNLIMITED);

    private Security() {
    }

    public static Optional<Tenant> tenant(View view, int id) {
        return id == DEFAULT.id() ? Optional.of(view.get(TENANTS, id).orElse(DEFAULT)) : view.get(TENANTS, id);
    }

    public static Optional<Tenant> tenant(View view, String name) {
        if (name.equalsIgnoreCase(DEFAULT.name())) {
            return tenant(view, DEFAULT.id());
        }
        return view.scan(TENANTS).values().filter(tenant -> tenant.name().equalsIgnoreCase(name)).findFirst();
    }

    public static Tenant requireTenant(View view, String name) {
        return tenant(view, name).orElseThrow(() -> HStoreException.invalid("unknown tenant '" + name + "'"));
    }

    public static List<Tenant> tenants(View view) {
        List<Tenant> stored = view.scan(TENANTS).values().toList();
        return stored.stream().anyMatch(tenant -> tenant.id() == DEFAULT.id())
                ? stored
                : Stream.concat(Stream.of(DEFAULT), stored.stream()).toList();
    }

    public static Tenant createTenant(Transaction txn, String name, Quota quota) {
        if (tenant(txn, name).isPresent()) {
            throw HStoreException.invalid("tenant '" + name + "' already exists");
        }
        int id = Math.max(1, txn.scan(TENANTS).last().map(entry -> (int) entry.key() + 1).orElse(1));
        Tenant tenant = new Tenant(id, name, quota);
        txn.merge(TENANTS, id, Optional::isEmpty, _ -> Optional.of(tenant));
        return tenant;
    }

    public static Tenant setQuota(Transaction txn, Tenant tenant, Quota quota) {
        Tenant updated = new Tenant(tenant.id(), tenant.name(), quota);
        txn.put(TENANTS, tenant.id(), updated);
        return updated;
    }

    public static Quota quota(View view, int tenant) {
        return tenant(view, tenant).map(Tenant::quota).orElse(Quota.UNLIMITED);
    }

    public static Usage usage(View view, int tenant) {
        return view.get(USAGE, tenant).orElse(Usage.NONE);
    }

    public static void charge(Transaction txn, int tenant, Usage delta) {
        Quota quota = quota(txn, tenant);
        Usage projected = usage(txn, tenant).plus(delta);
        boolean growing = delta.atoms() > 0 || delta.edges() > 0 || delta.payloadBytes() > 0;
        if (growing && !quota.admits(projected)) {
            throw HStoreException.limit("tenant #" + tenant + " would exceed its quota: usage " + projected + " against " + quota);
        }
        txn.merge(USAGE, tenant,
                current -> delta.atoms() <= 0 && delta.edges() <= 0 && delta.payloadBytes() <= 0
                        || quota.admits(current.orElse(Usage.NONE).plus(delta)),
                current -> Optional.of(current.orElse(Usage.NONE).plus(delta)));
    }

    public static void createUser(Transaction txn, String name, String password, int tenant, Role role) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_.@-]*")) {
            throw HStoreException.invalid("invalid user name '" + name + "'");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        User user = new User(name, HEX.formatHex(salt), HEX.formatHex(derive(password, salt, ITERATIONS)), ITERATIONS, tenant, role);
        txn.merge(USERS, userKey(name), Optional::isEmpty, _ -> Optional.of(user));
    }

    public static void setPassword(Transaction txn, String name, String password) {
        User current = user(txn, name).orElseThrow(() -> HStoreException.invalid("unknown user '" + name + "'"));
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        txn.put(USERS, userKey(name), new User(name, HEX.formatHex(salt), HEX.formatHex(derive(password, salt, ITERATIONS)),
                ITERATIONS, current.tenant(), current.role()));
    }

    public static void dropUser(Transaction txn, String name) {
        user(txn, name).orElseThrow(() -> HStoreException.invalid("unknown user '" + name + "'"));
        txn.delete(USERS, userKey(name));
    }

    public static Optional<User> user(View view, String name) {
        return view.get(USERS, userKey(name)).filter(user -> user.name().equals(name));
    }

    public static List<User> users(View view) {
        return view.scan(USERS).values().toList();
    }

    public static Optional<Principal> authenticate(View view, String name, String password) {
        return user(view, name).filter(user -> MessageDigest.isEqual(
                        derive(password, HEX.parseHex(user.salt()), user.iterations()), HEX.parseHex(user.hash())))
                .map(user -> new Principal(user.name(), user.tenant(), user.role()));
    }

    public static boolean hasUsers(View view) {
        return !view.scan(USERS).isEmpty();
    }

    private static long userKey(String name) {
        return Hashing.of(name);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", e);
        }
    }
}
