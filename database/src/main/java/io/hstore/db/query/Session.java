// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.Writer;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Scram;
import io.hstore.db.security.Security;
import io.hstore.db.security.SignIns;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.txn.Isolation;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

public final class Session implements AutoCloseable {

    private final HypergraphDatabase database;
    private final Map<String, Long> variables = new HashMap<>();
    private final Executor executor = new Executor(this);
    private Optional<Transaction> transaction = Optional.empty();
    private int branch = Branch.MAIN;
    private OptionalLong pinnedGeneration = OptionalLong.empty();
    private Principal principal;
    private boolean authenticated;
    private Optional<Security.User> credentials = Optional.empty();
    private boolean renewing;
    private Optional<String> origin = Optional.empty();
    private int failedAuthentications;
    private Optional<ScramAttempt> scram = Optional.empty();
    private Optional<String> scramUser = Optional.empty();
    private Optional<String> clearTextRefusal = Optional.empty();

    private record ScramAttempt(Scram.Server server, SignIns.Reservation reservation) {
    }
    private Format format = Format.TABLE;

    public enum Format { TABLE, JSON }

    public static final int MAX_FAILED_AUTHENTICATIONS = 5;

    public Session(HypergraphDatabase database) {
        this(database, Principal.SYSTEM);
    }

    public Session(HypergraphDatabase database, Principal principal) {
        this.database = database;
        this.principal = principal;
    }

    public Principal principal() {
        return principal;
    }

    public OptionalLong generation() {
        return pinnedGeneration;
    }

    public Format format() {
        return format;
    }

    public String render(QueryResult result) {
        return format == Format.JSON ? result.toJson() : result.render();
    }

    public boolean authenticate(String user, String password) {
        requireCanAuthenticate();
        Optional<Principal> found = database.authenticate(user, password, origin);
        if (found.isEmpty()) {
            failedAuthentications++;
            return false;
        }
        signIn(found.get());
        return true;
    }

    public String scramFirst(String mechanism, String clientFirst, Optional<byte[]> channelBinding) {
        requireCanAuthenticate();
        scram.ifPresent(pending -> pending.reservation().failed());
        scram = Optional.empty();
        Scram.Server server = database.scram(channelBinding);
        String serverFirst = server.first(mechanism, clientFirst);
        scramUser = Optional.of(server.user());
        SignIns.Reservation reservation = database.signIns().reserve(server.user(), origin);
        scram = Optional.of(new ScramAttempt(server, reservation));
        return serverFirst;
    }

    public Optional<String> scramFinal(String clientFinal) {
        ScramAttempt attempt = scram.orElseThrow(() -> HStoreException.invalid("malformed SCRAM message: no exchange in progress"));
        scram = Optional.empty();
        Scram.Outcome outcome;
        try {
            outcome = attempt.server().last(clientFinal);
        } catch (RuntimeException malformed) {
            attempt.reservation().failed();
            failedAuthentications++;
            throw malformed;
        }
        Optional<Principal> found = outcome instanceof Scram.Outcome.Accepted(String user, String _)
                ? database.read(reader -> Security.user(reader.engineView(), user)).map(Security::principal)
                : Optional.empty();
        if (found.isEmpty()) {
            attempt.reservation().failed();
            failedAuthentications++;
            return Optional.empty();
        }
        attempt.reservation().succeeded();
        signIn(found.get());
        return Optional.of(((Scram.Outcome.Accepted) outcome).serverFinal());
    }

    public Optional<String> scramUser() {
        return scramUser;
    }

    public void refuseClearTextPasswords(String reason) {
        clearTextRefusal = Optional.of(reason);
    }

    private void requireCanAuthenticate() {
        if (transaction.isPresent()) {
            throw HStoreException.invalid("cannot authenticate inside a transaction");
        }
        if (failedAuthentications >= MAX_FAILED_AUTHENTICATIONS) {
            throw HStoreException.invalid("too many failed authentication attempts in this session");
        }
    }

    private void signIn(Principal found) {
        principal = found;
        authenticated = true;
        credentials = database.read(reader -> Security.user(reader.engineView(), found.user()));
        renewing = false;
        variables.clear();
        branch = Branch.MAIN;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public void origin(String address) {
        origin = Optional.of(address);
    }

    public boolean credentialsCurrent() {
        if (!authenticated) {
            return true;
        }
        Optional<Security.User> stored = database.read(reader -> Security.user(reader.engineView(), principal.user()));
        boolean current = stored.isPresent() && credentials.isPresent()
                && stored.get().tenant() == credentials.get().tenant() && stored.get().role() == credentials.get().role()
                && (renewing || stored.get().hash().equals(credentials.get().hash()));
        if (current && renewing && transaction.isEmpty()) {
            credentials = stored;
            renewing = false;
        }
        return current;
    }

    public HypergraphDatabase database() {
        return database;
    }

    public QueryResult execute(String source) {
        List<QueryResult> results = executeAll(source);
        return results.isEmpty() ? QueryResult.message("nothing to execute") : results.getLast();
    }

    public List<QueryResult> executeAll(String source) {
        List<QueryResult> results = new ArrayList<>();
        for (Statement statement : Parser.script(source)) {
            results.add(execute(statement));
        }
        return results;
    }

    public QueryResult execute(Statement statement) {
        if (!(statement instanceof Statement.Authenticate) && !credentialsCurrent()) {
            close();
            throw HStoreException.invalid("user " + principal.user() + " was dropped or its password changed; authenticate again");
        }
        QueryResult result = dispatch(statement);
        if (statement instanceof Statement.AlterUser(String name, String _) && authenticated && name.equals(principal.user())) {
            renewing = true;
        }
        return result;
    }

    private QueryResult dispatch(Statement statement) {
        return switch (statement) {
            case Statement.Begin(boolean serializable) -> begin(serializable);
            case Statement.Commit _ -> commit();
            case Statement.Rollback _ -> rollback();
            case Statement.UseBranch(Optional<String> name) -> use(name);
            case Statement.At(long generation, Statement inner) -> pinned(generation, inner);
            case Statement.AsOf(long wallTime, Statement inner) -> pinned(database.engine().transactions().generationAsOf(wallTime)
                    .orElseThrow(() -> HStoreException.invalid("no retained generation precedes " + wallTime)).id(), inner);
            case Statement.UseTenant(String name) -> {
                if (transaction.isPresent()) {
                    throw HStoreException.invalid("cannot switch tenants inside a transaction");
                }
                Security.Tenant tenant = database.read(reader -> Security.tenant(reader.engineView(), name))
                        .filter(found -> principal.global() || found.id() == principal.tenant())
                        .orElseThrow(() -> principal.global()
                                ? HStoreException.invalid("unknown tenant '" + name + "'")
                                : HStoreException.invalid("user " + principal.user() + " may not switch tenants"));
                principal = principal.inTenant(tenant.id());
                yield QueryResult.message("using tenant " + tenant.name());
            }
            case Statement.Authenticate(String user, String password) -> {
                if (clearTextRefusal.isPresent()) {
                    throw HStoreException.invalid(clearTextRefusal.get());
                }
                if (!authenticate(user, password)) {
                    throw HStoreException.invalid("authentication failed for user " + user);
                }
                yield QueryResult.message("authenticated as " + user);
            }
            case Statement.WhoAmI _ -> QueryResult.table(List.of("user", "tenant", "role", "branch"), List.of(List.of(
                    QueryResult.text(principal.user()),
                    QueryResult.text(database.read(reader -> Security.tenant(reader.engineView(), principal.tenant()).map(Security.Tenant::name).orElse("#" + principal.tenant()))),
                    QueryResult.text(principal.role().name()), QueryResult.number(branch))));
            case Statement.SetFormat(String name) -> {
                format = Arrays.stream(Format.values()).filter(candidate -> candidate.name().equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> HStoreException.invalid("unknown output format '" + name + "'; expected table or json"));
                yield QueryResult.message("output format " + name);
            }
            default -> executor.run(statement);
        };
    }

    private QueryResult begin(boolean serializable) {
        if (transaction.isPresent()) {
            throw HStoreException.invalid("a transaction is already open");
        }
        TxnOptions options = database.transactionOptions(principal).onBranch(branch).withIsolation(serializable ? Isolation.SERIALIZABLE : Isolation.SNAPSHOT);
        Transaction txn = database.engine().begin(options);
        transaction = Optional.of(txn);
        return QueryResult.message("transaction " + txn.id() + " started at generation " + txn.generation());
    }

    private QueryResult commit() {
        Transaction txn = transaction.orElseThrow(() -> HStoreException.invalid("no open transaction"));
        transaction = Optional.empty();
        database.commit(txn);
        return QueryResult.message("committed transaction " + txn.id() + " at generation " + database.engine().transactions().current().id());
    }

    private QueryResult rollback() {
        Transaction txn = transaction.orElseThrow(() -> HStoreException.invalid("no open transaction"));
        transaction = Optional.empty();
        txn.abort();
        return QueryResult.message("rolled back transaction " + txn.id());
    }

    private QueryResult use(Optional<String> name) {
        if (transaction.isPresent()) {
            throw HStoreException.invalid("cannot switch branches inside a transaction");
        }
        if (name.isPresent() && !name.get().equalsIgnoreCase("main")) {
            principal.requireGlobal();
        }
        branch = name.map(this::branchId).orElse(Branch.MAIN);
        return QueryResult.message("using branch " + name.orElse("main"));
    }

    int branchId(String name) {
        if (name.equalsIgnoreCase("main")) {
            return Branch.MAIN;
        }
        return database.engine().branches().stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> HStoreException.invalid("unknown branch '" + name + "'")).id();
    }

    private QueryResult pinned(long generation, Statement inner) {
        OptionalLong previous = pinnedGeneration;
        pinnedGeneration = OptionalLong.of(generation);
        try {
            return dispatch(inner);
        } finally {
            pinnedGeneration = previous;
        }
    }

    <T> T read(Function<Reader, T> work) {
        if (transaction.isPresent()) {
            return work.apply(database.writer(transaction.get(), principal));
        }
        if (pinnedGeneration.isPresent()) {
            return database.readAt(pinnedGeneration.getAsLong(), branch, principal, work::apply);
        }
        return database.read(branch, principal, work::apply);
    }

    <T> T write(Function<Writer, T> work) {
        if (pinnedGeneration.isPresent()) {
            throw HStoreException.invalid("historical generations are read-only");
        }
        if (transaction.isPresent()) {
            return work.apply(database.writer(transaction.get(), principal));
        }
        return database.write(database.transactionOptions(principal).onBranch(branch), principal, work::apply);
    }

    long resolve(Reader reader, Ast.Ref ref) {
        return switch (ref) {
            case Ast.Ref.Id(long id) -> id;
            case Ast.Ref.Keyed(String type, String key) -> reader.resolve(type, key);
            case Ast.Ref.Variable(String name) -> {
                Long bound = variables.get(name);
                if (bound == null) {
                    throw HStoreException.invalid("variable $" + name + " is not bound");
                }
                yield bound;
            }
        };
    }

    void bind(String name, long atom) {
        variables.put(name, atom);
    }

    public Map<String, Long> variables() {
        return Map.copyOf(variables);
    }

    public int branch() {
        return branch;
    }

    public boolean inTransaction() {
        return transaction.isPresent();
    }

    @Override
    public void close() {
        transaction.ifPresent(Transaction::abort);
        transaction = Optional.empty();
        scram.ifPresent(pending -> pending.reservation().failed());
        scram = Optional.empty();
    }
}
