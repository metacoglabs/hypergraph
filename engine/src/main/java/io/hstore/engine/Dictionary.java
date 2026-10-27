// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Symbol;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeCursor;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.TransactionManager;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

public final class Dictionary {

    public static final int ROLE = 1;
    public static final int ROLE_SET = 2;
    public static final int NO_ROLES = 0;
    public static final int NAME_LIMIT = 128;

    private final TransactionManager transactions;
    private final ConcurrentHashMap<Symbol, Integer> ids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Symbol> symbols = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<String>> roleLists = new ConcurrentHashMap<>();
    private final Object roleSetsLock = new Object();
    private final Map<Integer, Symbol> rememberedRoleSets = new HashMap<>();
    private volatile Map<Integer, BitSet> roleSetsByRole;

    Dictionary(TransactionManager transactions) {
        this.transactions = transactions;
    }

    public int intern(Symbol symbol) {
        Integer cached = ids.get(symbol);
        if (cached != null) {
            return cached;
        }
        OptionalInt existing = lookup(symbol);
        int id = existing.isPresent() ? existing.getAsInt() : transactions.system(Branch.MAIN, workspace -> {
            OptionalInt raced = find(workspace.tree(EngineSlots.SYMBOL_LOOKUP), workspace.tree(EngineSlots.SYMBOLS), symbol);
            if (raced.isPresent()) {
                return raced.getAsInt();
            }
            int next = workspace.tree(EngineSlots.SYMBOLS).last().map(entry -> (int) entry.key() + 1).orElse(1);
            workspace.write(EngineSlots.SYMBOLS, next, _ -> Optional.of(symbol));
            return next;
        });
        remember(id, symbol);
        return id;
    }

    public static String requireName(String kind, String text) {
        if (text.length() > NAME_LIMIT) {
            throw HStoreException.invalid("a " + kind + " can be at most " + NAME_LIMIT + " characters long");
        }
        return text;
    }

    public int name(int namespace, String text) {
        return intern(new Symbol.Name(namespace, text));
    }

    public int group(int namespace, Collection<Integer> members) {
        return members.isEmpty() ? NO_ROLES : intern(new Symbol.Group(namespace, List.copyOf(members)));
    }

    public Optional<Symbol> symbol(int id) {
        Symbol cached = symbols.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        try (Snapshot snapshot = transactions.snapshot(Branch.MAIN)) {
            Optional<Symbol> found = snapshot.symbol(id);
            found.ifPresent(symbol -> remember(id, symbol));
            return found;
        }
    }

    public String nameOf(int id) {
        return symbol(id).map(symbol -> symbol instanceof Symbol.Name(int _, String text) ? text : symbol.toString())
                .orElseThrow(() -> HStoreException.invalid("unknown symbol " + id));
    }

    public List<Integer> membersOf(int id) {
        if (id == NO_ROLES) {
            return List.of();
        }
        return symbol(id).map(symbol -> symbol instanceof Symbol.Group(int _, List<Integer> members) ? members : List.<Integer>of())
                .orElseThrow(() -> HStoreException.invalid("unknown symbol group " + id));
    }

    public int roleSet(Collection<String> roles) {
        return group(ROLE_SET, roles.stream().map(role -> name(ROLE, role)).toList());
    }

    public List<String> roles(int roleSet) {
        if (roleSet == NO_ROLES) {
            return List.of();
        }
        List<String> cached = roleLists.get(roleSet);
        if (cached != null) {
            return cached;
        }
        List<String> resolved = membersOf(roleSet).stream().map(this::nameOf).toList();
        roleLists.putIfAbsent(roleSet, resolved);
        return resolved;
    }

    public OptionalInt roleId(String role) {
        return lookup(new Symbol.Name(ROLE, role));
    }

    public List<Integer> roleSetsContaining(String role) {
        return roleSetsWithAll(List.of(role)).stream().boxed().toList();
    }

    public BitSet roleSetsWithAll(Collection<String> roles) {
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        List<OptionalInt> roleIds = roles.stream().distinct().map(this::roleId).toList();
        if (roleIds.stream().anyMatch(OptionalInt::isEmpty)) {
            return new BitSet();
        }
        Map<Integer, BitSet> byRole = roleSetsByRole();
        BitSet accepted = (BitSet) byRole.getOrDefault(roleIds.getFirst().getAsInt(), new BitSet()).clone();
        roleIds.stream().skip(1).forEach(id -> accepted.and(byRole.getOrDefault(id.getAsInt(), new BitSet())));
        return accepted;
    }

    private Map<Integer, BitSet> roleSetsByRole() {
        Map<Integer, BitSet> loaded = roleSetsByRole;
        if (loaded != null) {
            return loaded;
        }
        synchronized (roleSetsLock) {
            if (roleSetsByRole == null) {
                Map<Integer, BitSet> byRole = new HashMap<>();
                try (Snapshot snapshot = transactions.snapshot(Branch.MAIN)) {
                    TreeCursor<Symbol> cursor = snapshot.scan(EngineSlots.SYMBOLS).cursor();
                    for (boolean found = cursor.seek(0); found && cursor.hasNext(); cursor.advance()) {
                        addRoleSet(byRole, (int) cursor.key(), cursor.value());
                    }
                }
                rememberedRoleSets.forEach((id, symbol) -> addRoleSet(byRole, id, symbol));
                rememberedRoleSets.clear();
                roleSetsByRole = Map.copyOf(byRole);
            }
            return roleSetsByRole;
        }
    }

    private static void addRoleSet(Map<Integer, BitSet> byRole, int id, Symbol symbol) {
        if (symbol instanceof Symbol.Group(int namespace, List<Integer> members) && namespace == ROLE_SET) {
            members.forEach(role -> byRole.compute(role, (_, current) -> {
                BitSet updated = current == null ? new BitSet() : (BitSet) current.clone();
                updated.set(id);
                return updated;
            }));
        }
    }

    public OptionalInt lookup(Symbol symbol) {
        Integer cached = ids.get(symbol);
        if (cached != null) {
            return OptionalInt.of(cached);
        }
        try (Snapshot snapshot = transactions.snapshot(Branch.MAIN)) {
            OptionalInt found = find(snapshot.scan(EngineSlots.SYMBOL_LOOKUP), snapshot.scan(EngineSlots.SYMBOLS), symbol);
            found.ifPresent(id -> remember(id, symbol));
            return found;
        }
    }

    private static OptionalInt find(Tree<Postings<Marker>> lookup, Tree<Symbol> symbols, Symbol symbol) {
        return EngineSlots.SYMBOL_INDEX.stream(lookup, symbol.hash())
                .mapToLong(Entry::key)
                .filter(id -> symbols.get(id).map(symbol::equals).orElse(false))
                .mapToInt(id -> (int) id)
                .findFirst();
    }

    private void remember(int id, Symbol symbol) {
        ids.put(symbol, id);
        symbols.put(id, symbol);
        if (symbol instanceof Symbol.Group group && group.namespace() == ROLE_SET) {
            synchronized (roleSetsLock) {
                if (roleSetsByRole == null) {
                    rememberedRoleSets.put(id, symbol);
                } else {
                    Map<Integer, BitSet> byRole = new HashMap<>(roleSetsByRole);
                    addRoleSet(byRole, id, symbol);
                    roleSetsByRole = Map.copyOf(byRole);
                }
            }
        }
    }
}
