// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.db.security.Quota.Usage;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class Security {

    public record Tenant(int id, String name, Quota quota) {
    }

    public record User(String name, String salt, String hash, int iterations, int tenant, Role role) {
    }

    public static final int ITERATIONS = 600_000;
    private static final System.Logger LOG = System.getLogger("hstore.security");
    private static final long SCRAM_SECRET = 0x5343_5241_4D00_0000L;
    private static final int SCRAM_SECRET_WORDS = 4;
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 1024;
    private static final byte[] UNKNOWN_USER_SALT = new byte[16];
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

    public static final List<Slot<?>> SLOTS = List.of(TENANTS, USERS, USAGE, Access.GRANTS, Access.USER_OPTIONS, Access.TENANT_OPTIONS);

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
        Usage projected;
        try (View.Unrecorded _ = txn.unrecorded()) {
            projected = usage(txn, tenant).plus(delta);
        }
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
        Scram.Verifier verifier = secret(password);
        User user = new User(name, HEX.formatHex(verifier.salt()), verifier.encode(), verifier.iterations(), tenant, role);
        txn.merge(USERS, userKey(name), Optional::isEmpty, _ -> Optional.of(user));
    }

    public static void setPassword(Transaction txn, String name, String password) {
        User current = user(txn, name).orElseThrow(() -> HStoreException.invalid("unknown user '" + name + "'"));
        Scram.Verifier verifier = secret(password);
        txn.put(USERS, userKey(name), new User(name, HEX.formatHex(verifier.salt()), verifier.encode(), verifier.iterations(),
                current.tenant(), current.role()));
    }

    static Scram.Verifier secret(String password) {
        if (password.startsWith(Scram.PREFIX)) {
            return Scram.Verifier.parse(password).filter(given -> given.salt().length >= 8)
                    .orElseThrow(() -> HStoreException.invalid("not a valid SCRAM-SHA-256 verifier: it needs " + Scram.MIN_ITERATIONS
                            + " to " + Scram.MAX_ITERATIONS + " iterations, a salt of at least 8 bytes and 32-byte keys"));
        }
        return newVerifier(password);
    }

    public static Scram.Verifier newVerifier(String password) {
        requirePassword(password);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Scram.Verifier.of(password, salt, ITERATIONS);
    }

    public static Optional<Scram.Verifier> verifier(User user) {
        return Scram.Verifier.parse(user.hash());
    }

    public static boolean legacy(User user) {
        return !user.hash().startsWith(Scram.PREFIX);
    }

    public static List<String> legacyUsers(View view) {
        return users(view).stream().filter(Security::legacy).map(User::name).sorted().toList();
    }

    public static User replaceLegacy(Transaction txn, User legacy, String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        Scram.Verifier verifier = Scram.Verifier.of(password, salt, ITERATIONS);
        User converted = new User(legacy.name(), HEX.formatHex(salt), verifier.encode(), ITERATIONS, legacy.tenant(), legacy.role());
        txn.merge(USERS, userKey(legacy.name()), current -> current.equals(Optional.of(legacy)), _ -> Optional.of(converted));
        return converted;
    }

    private static boolean matches(User user, String password) {
        if (legacy(user)) {
            return MessageDigest.isEqual(derive(password, HEX.parseHex(user.salt()), user.iterations()), HEX.parseHex(user.hash()));
        }
        return verifier(user).filter(verifier -> verifier.matches(password)).isPresent();
    }

    public static Optional<byte[]> scramSecret(View view) {
        byte[] secret = new byte[SCRAM_SECRET_WORDS * Long.BYTES];
        for (int word = 0; word < SCRAM_SECRET_WORDS; word++) {
            Optional<Long> value = view.get(EngineSlots.SETTINGS, SCRAM_SECRET + word);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            for (int i = 0; i < Long.BYTES; i++) {
                secret[word * Long.BYTES + i] = (byte) (value.get() >>> (8 * i));
            }
        }
        return Optional.of(secret);
    }

    public static void createScramSecret(Transaction txn) {
        for (int word = 0; word < SCRAM_SECRET_WORDS; word++) {
            long value = RANDOM.nextLong();
            txn.merge(EngineSlots.SETTINGS, SCRAM_SECRET + word, Optional::isEmpty, _ -> Optional.of(value));
        }
    }

    public static Principal principal(User user) {
        return new Principal(user.name(), user.tenant(), user.role());
    }

    public static void requirePassword(String password) {
        int length = password.codePointCount(0, password.length());
        if (length < MIN_PASSWORD_LENGTH || length > MAX_PASSWORD_LENGTH) {
            throw HStoreException.invalid("a password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters long");
        }
    }

    public static void dropUser(Transaction txn, String name) {
        user(txn, name).orElseThrow(() -> HStoreException.invalid("unknown user '" + name + "'"));
        txn.delete(USERS, userKey(name));
        Access.forget(txn, name);
    }

    public static void setRole(Transaction txn, String name, Role role) {
        User current = user(txn, name).orElseThrow(() -> HStoreException.invalid("unknown user '" + name + "'"));
        txn.put(USERS, userKey(name), new User(current.name(), current.salt(), current.hash(), current.iterations(), current.tenant(), role));
    }

    public static void keepUnchanged(Transaction txn, User user) {
        txn.merge(USERS, userKey(user.name()), current -> current.equals(Optional.of(user)), current -> current);
    }

    public static long globalAdmins(View view) {
        return users(view).stream().filter(Security::globalAdmin).count();
    }

    public static boolean globalAdmin(User user) {
        return user.tenant() == Principal.DEFAULT_TENANT && user.role() == Role.ADMIN;
    }

    public static boolean hasGlobalAdmin(View view) {
        return users(view).stream().anyMatch(Security::globalAdmin);
    }

    public static void keepAGlobalAdmin(Transaction txn, boolean hadGlobalAdmin) {
        List<User> all = users(txn);
        if (all.isEmpty() || !hadGlobalAdmin && all.stream().noneMatch(Security::globalAdmin)) {
            return;
        }
        List<User> admins = all.stream().filter(user -> globalAdmin(user) && neverExpires(Access.options(txn, user.name()))).toList();
        if (admins.isEmpty()) {
            throw HStoreException.invalid("this would leave no admin of the default tenant whose password never expires; "
                    + "create or restore one first");
        }
        for (User admin : admins) {
            txn.merge(USERS, userKey(admin.name()), current -> current.filter(user -> user.name().equals(admin.name()) && globalAdmin(user)).isPresent(),
                    current -> current);
            txn.merge(Access.USER_OPTIONS, Hashing.of(admin.name()), current -> neverExpires(current.filter(options -> options.user().equals(admin.name()))
                    .orElseGet(() -> Access.Options.defaults(admin.name()))), current -> current);
        }
    }

    private static boolean neverExpires(Access.Options options) {
        return options.validUntil() == Long.MAX_VALUE;
    }

    public static boolean expired(View view, String name) {
        boolean expired = Access.options(view, name).expired(System.currentTimeMillis());
        if (expired) {
            LOG.log(System.Logger.Level.WARNING, "user \"{0}\" tried to sign in after its password expired", name);
        }
        return expired;
    }

    public static Optional<User> user(View view, String name) {
        return view.get(USERS, userKey(name)).filter(user -> user.name().equals(name));
    }

    public static List<User> users(View view) {
        return view.scan(USERS).values().toList();
    }

    public static Optional<Principal> authenticate(View view, String name, String password) {
        return signIn(view, name, password).map(Security::principal);
    }

    public static Optional<User> signIn(View view, String name, String password) {
        Optional<User> stored = user(view, name);
        if (stored.isEmpty()) {
            derive(password, UNKNOWN_USER_SALT, ITERATIONS);
        }
        return stored.filter(user -> matches(user, password)).filter(user -> !expired(view, user.name()));
    }

    public static boolean hasUsers(View view) {
        return !view.scan(USERS).isEmpty();
    }

    private static long userKey(String name) {
        return Hashing.of(name);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        return Scram.saltedPassword(password, salt, iterations);
    }
}
