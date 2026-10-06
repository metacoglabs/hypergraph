// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import io.hstore.db.query.Ast.Statement;

final class StatementLogger {

    enum Mode { NONE, DDL, MOD, ALL }

    private enum Category { DDL, MOD, READ }

    private static final System.Logger LOG = System.getLogger("hstore.statement");

    private final Mode mode;
    private final long slowMillis;

    StatementLogger(Mode mode, long slowMillis) {
        this.mode = mode;
        this.slowMillis = slowMillis;
    }

    static StatementLogger from(ServerConfig config) {
        return new StatementLogger(Mode.valueOf(config.string(Setting.LOG_STATEMENT).toUpperCase()), config.number(Setting.LOG_MIN_DURATION_MS));
    }

    static StatementLogger silent() {
        return new StatementLogger(Mode.NONE, -1);
    }

    void executed(String origin, String user, Statement statement, long millis, String script) {
        boolean slow = slowMillis >= 0 && millis >= slowMillis;
        boolean logged = switch (mode) {
            case NONE -> false;
            case ALL -> true;
            case MOD -> category(statement) != Category.READ;
            case DDL -> category(statement) == Category.DDL;
        };
        if (slow || logged) {
            LOG.log(System.Logger.Level.INFO, "{0} user {1} duration: {2} ms  statement: {3} {4}",
                    origin, user, millis, statement.getClass().getSimpleName(), abbreviate(script));
        }
    }

    private static Category category(Statement statement) {
        return switch (statement) {
            case Statement.CreateType _, Statement.CreateIndex _, Statement.CreateJsonIndex _, Statement.CreateView _,
                 Statement.CreateBranch _, Statement.DropBranch _, Statement.CreateTenant _, Statement.AlterTenant _,
                 Statement.CreateUser _, Statement.AlterUser _, Statement.DropUser _ -> Category.DDL;
            case Statement.Query _, Statement.Explain _, Statement.Members _, Statement.IncidentTo _, Statement.Describe _,
                 Statement.Gather _, Statement.Reduce _, Statement.Scatter _, Statement.Propagate _, Statement.Neighbors _,
                 Statement.OverlapJoin _, Statement.Closure _, Statement.PatternQuery _, Statement.Expand _, Statement.History _,
                 Statement.Stats _, Statement.ShowTypes _, Statement.ShowBranches _, Statement.ShowViews _, Statement.ShowView _,
                 Statement.ShowTenants _, Statement.ShowUsers _, Statement.WhoAmI _, Statement.DiffBranches _,
                 Statement.DiffGenerations _, Statement.ResolveSignals _, Statement.Trace _ -> Category.READ;
            default -> Category.MOD;
        };
    }

    private static String abbreviate(String script) {
        String masked = script.replaceAll("\\s+", " ").strip().replaceAll("(?i)PASSWORD\\s+'[^']*'", "PASSWORD '***'");
        return masked.substring(0, Math.min(200, masked.length()));
    }
}
