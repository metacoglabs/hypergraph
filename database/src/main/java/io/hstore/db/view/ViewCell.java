// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.view;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;

public sealed interface ViewCell {

    long hash();

    record Count(long value) implements ViewCell {
        @Override
        public long hash() {
            return Hashing.of(1, value);
        }
    }

    record Activity(long added, long removed) implements ViewCell {
        @Override
        public long hash() {
            return Hashing.of(2, added, removed);
        }
    }

    record Ranked(List<Neighbor> neighbors) implements ViewCell {
        public Ranked {
            neighbors = List.copyOf(neighbors);
        }

        @Override
        public long hash() {
            return neighbors.stream().mapToLong(neighbor -> Hashing.of(neighbor.edge(), neighbor.overlap())).reduce(3, Hashing::combine);
        }
    }

    record Neighbor(long edge, long overlap) {
    }

    ValueCodec<ViewCell> CODEC = ValueCodec.rows(
            cell -> switch (cell) {
                case Count _ -> 11;
                case Activity _ -> 21;
                case Ranked(List<Neighbor> neighbors) -> 6 + 20 * neighbors.size();
            },
            (out, cell) -> {
                switch (cell) {
                    case Count(long value) -> out.putByte(0).putSignedVarLong(value);
                    case Activity(long added, long removed) -> out.putByte(1).putVarLong(added).putVarLong(removed);
                    case Ranked(List<Neighbor> neighbors) -> {
                        out.putByte(2).putVarInt(neighbors.size());
                        neighbors.forEach(neighbor -> out.putVarLong(neighbor.edge()).putVarLong(neighbor.overlap()));
                    }
                }
            },
            ViewCell::read);

    private static ViewCell read(ByteCursor in) {
        return switch (in.getUnsignedByte()) {
            case 0 -> new Count(in.getSignedVarLong());
            case 1 -> new Activity(in.getVarLong(), in.getVarLong());
            default -> {
                int count = in.getVarInt();
                List<Neighbor> neighbors = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    neighbors.add(new Neighbor(in.getVarLong(), in.getVarLong()));
                }
                yield new Ranked(neighbors);
            }
        };
    }
}
