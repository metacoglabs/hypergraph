// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

public sealed interface GroupKernel<R> {

    record Streaming<R>(Function<Stream<Gathered>, R> function) implements GroupKernel<R> {
    }

    record TwoPass<S, R>(Function<Stream<Gathered>, S> summarize, BiFunction<S, Stream<Gathered>, R> finish) implements GroupKernel<R> {
    }

    record Materialize<R>(Function<List<Gathered>, R> function) implements GroupKernel<R> {
    }
}
