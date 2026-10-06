// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.evidence;

import io.hstore.db.Member;
import io.hstore.db.Reader;
import io.hstore.db.Writer;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.TreeSchema;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class Provenance {

    public sealed interface Step {
        int depth();

        record Assertion(int depth, long id, Qualifier qualifier) implements Step {
        }

        record Support(int depth, long id, Evidence evidence) implements Step {
        }

        record Source(int depth, long atom) implements Step {
        }

        record Cycle(int depth, long id) implements Step {
        }
    }

    private record Visit(long id, int depth) {
    }

    public static final Slot<Qualifier> QUALIFIERS = Slot.primary(37, "qualifiers",
            new TreeSchema<>(73, "qualifiers", FingerprintMode.SET, Qualifier.CODEC, EntryMeasure.keyed(Qualifier::hash)));

    public static final Slot<Evidence> EVIDENCE = Slot.primary(38, "evidence",
            new TreeSchema<>(74, "evidence", FingerprintMode.SET, Evidence.CODEC, EntryMeasure.keyed(Evidence::hash)));

    public static final List<Slot<?>> SLOTS = List.of(QUALIFIERS, EVIDENCE);

    private Provenance() {
    }

    public static long record(Writer writer, Evidence evidence) {
        writer.principal().requireWrite();
        long id = writer.database().engine().transactions().allocateAtom();
        writer.transaction().put(EVIDENCE, id, evidence.withTenant(writer.tenant()));
        return id;
    }

    public static long assertion(Writer writer, Qualifier qualifier) {
        writer.principal().requireWrite();
        long id = writer.database().engine().transactions().allocateAtom();
        writer.transaction().put(QUALIFIERS, id, qualifier.withTenant(writer.tenant()));
        return id;
    }

    public static long qualify(Writer writer, long edge, long member, Qualifier qualifier) {
        long id = assertion(writer, qualifier);
        writer.qualify(edge, member, id);
        return id;
    }

    public static Optional<Qualifier> qualifier(Reader reader, long id) {
        return id == 0 ? Optional.empty() : reader.view().get(QUALIFIERS, id).filter(found -> found.tenant() == reader.tenant());
    }

    public static Optional<Evidence> evidence(Reader reader, long id) {
        return reader.view().get(EVIDENCE, id).filter(found -> found.tenant() == reader.tenant());
    }

    public static Stream<Member> effective(Reader reader, long edge, EvidencePolicy policy) {
        return reader.members(edge).filter(member -> policy.admits(qualifier(reader, member.qualifier())));
    }

    public static List<Step> trace(Reader reader, long assertion, int maxDepth) {
        List<Step> steps = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<Visit> pending = new ArrayDeque<>();
        pending.push(new Visit(assertion, 0));
        while (!pending.isEmpty()) {
            Visit next = pending.pop();
            long id = next.id();
            int depth = next.depth();
            if (!visited.add(id)) {
                steps.add(new Step.Cycle(depth, id));
                continue;
            }
            Optional<Qualifier> qualifier = qualifier(reader, id);
            Optional<Evidence> evidence = evidence(reader, id);
            if (qualifier.isPresent()) {
                steps.add(new Step.Assertion(depth, id, qualifier.get()));
                if (depth < maxDepth) {
                    qualifier.get().evidence().reversed().forEach(support -> pending.push(new Visit(support, depth + 1)));
                }
            } else if (evidence.isPresent()) {
                steps.add(new Step.Support(depth, id, evidence.get()));
                if (depth < maxDepth && evidence.get().source() != 0) {
                    pending.push(new Visit(evidence.get().source(), depth + 1));
                }
            } else if (reader.visible(id)) {
                steps.add(new Step.Source(depth, id));
            }
        }
        return steps;
    }
}
