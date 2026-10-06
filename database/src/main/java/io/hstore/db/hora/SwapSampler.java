// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Writer;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.TxnOptions;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class SwapSampler {

    public record Sample(Branch branch, long seed, int accepted, int rejected) {
    }

    private record Tally(int accepted, int rejected) {
    }

    private SwapSampler() {
    }

    public static Sample run(HypergraphDatabase database, String edgeType, int swaps, long seed, String branchName) {
        Branch branch = database.engine().createBranch(branchName, Branch.MAIN);
        Tally tally = database.write(TxnOptions.defaults().onBranch(branch.id()), writer -> swap(writer, edgeType, swaps, seed));
        return new Sample(branch, seed, tally.accepted(), tally.rejected());
    }

    private static Tally swap(Writer writer, String edgeType, int swaps, long seed) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        List<Long> edges = writer.atoms(edgeType)
                .filter(edge -> writer.edge(edge).kind() == EdgeKind.SET && writer.cardinality(edge) > 0)
                .boxed()
                .toList();
        int accepted = 0;
        int rejected = 0;
        if (edges.size() < 2) {
            return new Tally(0, swaps);
        }
        for (int attempt = 0; attempt < swaps; attempt++) {
            long first = edges.get(random.nextInt(edges.size()));
            long second = edges.get(random.nextInt(edges.size()));
            Hyperedge left = writer.edge(first);
            Hyperedge right = writer.edge(second);
            Incidence a = left.at(random.nextLong(left.size()));
            Incidence b = right.at(random.nextLong(right.size()));
            if (first == second || a.member() == b.member() || right.contains(a.member()) || left.contains(b.member())) {
                rejected++;
                continue;
            }
            writer.transaction().remove(first, a.member());
            writer.transaction().remove(second, b.member());
            writer.transaction().insert(first, relocated(a, b.member()));
            writer.transaction().insert(second, relocated(b, a.member()));
            accepted++;
        }
        return new Tally(accepted, rejected);
    }

    private static Incidence relocated(Incidence slot, long member) {
        return new Incidence(member, slot.roleSet(), slot.weight(), slot.validFrom(), slot.validTo(), slot.dataRef(), slot.qualifier());
    }
}
