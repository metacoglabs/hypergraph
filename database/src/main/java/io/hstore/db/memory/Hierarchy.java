// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;

public record Hierarchy(long id, String name, int tenant, int model, String view, int type, int fanout, int capacity, boolean continuous,
                        long root, long generation, long leaves, double temperature, List<double[]> calibration, int version) {

    public Hierarchy {
        calibration = calibration.stream().map(double[]::clone).toList();
    }

    public Router.Calibration router() {
        return new Router.Calibration(temperature, calibration);
    }

    Hierarchy with(long newRoot, long newGeneration, long newLeaves) {
        return new Hierarchy(id, name, tenant, model, view, type, fanout, capacity, continuous, newRoot, newGeneration, newLeaves, temperature,
                calibration, version);
    }

    Hierarchy calibrated(double newTemperature, List<double[]> levels) {
        return new Hierarchy(id, name, tenant, model, view, type, fanout, capacity, continuous, root, generation, leaves, newTemperature, levels,
                version + 1);
    }

    long hash() {
        long levels = calibration.stream().mapToLong(line -> Hashing.of(Double.doubleToLongBits(line[0]), Double.doubleToLongBits(line[1])))
                .reduce(0, Hashing::combine);
        return Hashing.of(Hashing.of(id, Hashing.of(name), tenant, model, Hashing.of(view), type, fanout, capacity),
                Hashing.of(continuous ? 1 : 0, root, generation, leaves, Double.doubleToLongBits(temperature), levels, version));
    }

    static final ValueCodec<Hierarchy> CODEC = ValueCodec.rows(
            hierarchy -> 120 + ByteCursor.stringSize(hierarchy.name()) + ByteCursor.stringSize(hierarchy.view()) + 16 * hierarchy.calibration().size(),
            (out, hierarchy) -> {
                out.putVarLong(hierarchy.id()).putString(hierarchy.name()).putVarInt(hierarchy.tenant()).putVarInt(hierarchy.model())
                        .putString(hierarchy.view()).putVarInt(hierarchy.type()).putVarInt(hierarchy.fanout()).putVarInt(hierarchy.capacity())
                        .putByte(hierarchy.continuous() ? 1 : 0).putVarLong(hierarchy.root()).putVarLong(hierarchy.generation())
                        .putVarLong(hierarchy.leaves()).putDouble(hierarchy.temperature()).putVarInt(hierarchy.calibration().size());
                hierarchy.calibration().forEach(line -> out.putDouble(line[0]).putDouble(line[1]));
                out.putVarInt(hierarchy.version());
            },
            in -> {
                long id = in.getVarLong();
                String name = in.getString();
                int tenant = in.getVarInt();
                int model = in.getVarInt();
                String view = in.getString();
                int type = in.getVarInt();
                int fanout = in.getVarInt();
                int capacity = in.getVarInt();
                boolean continuous = in.getByte() != 0;
                long root = in.getVarLong();
                long generation = in.getVarLong();
                long leaves = in.getVarLong();
                double temperature = in.getDouble();
                List<double[]> calibration = new ArrayList<>();
                for (int count = in.getVarInt(); count > 0; count--) {
                    calibration.add(new double[]{in.getDouble(), in.getDouble()});
                }
                return new Hierarchy(id, name, tenant, model, view, type, fanout, capacity, continuous, root, generation, leaves, temperature,
                        calibration, in.getVarInt());
            });
}
