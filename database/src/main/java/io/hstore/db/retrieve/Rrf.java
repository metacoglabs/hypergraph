// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Rrf {

    public static final int K = 60;

    public record Fused<T>(T item, double score, List<Integer> ranks) {
        public Fused {
            ranks = List.copyOf(ranks);
        }

        int best() {
            return ranks.stream().filter(rank -> rank > 0).min(Integer::compare).orElse(Integer.MAX_VALUE);
        }

        int firstList() {
            int best = best();
            return best == Integer.MAX_VALUE ? ranks.size() : ranks.indexOf(best);
        }
    }

    private Rrf() {
    }

    public static double term(int rank) {
        return 1.0 / (K + rank);
    }

    public static <T> List<Fused<T>> combine(List<? extends List<T>> lists) {
        Map<T, int[]> ranks = new LinkedHashMap<>();
        for (int list = 0; list < lists.size(); list++) {
            int rank = 0;
            for (T item : lists.get(list)) {
                int[] found = ranks.computeIfAbsent(item, _ -> new int[lists.size()]);
                if (found[list] == 0) {
                    found[list] = ++rank;
                }
            }
        }
        List<Fused<T>> fused = new ArrayList<>(ranks.size());
        ranks.forEach((item, positions) -> {
            double score = 0;
            for (int position : positions) {
                if (position > 0) {
                    score += term(position);
                }
            }
            fused.add(new Fused<>(item, score, Arrays.stream(positions).boxed().toList()));
        });
        fused.sort(Comparator.<Fused<T>>comparingDouble(Fused::score).reversed()
                .thenComparingInt(Fused::best)
                .thenComparingInt(Fused::firstList));
        return fused;
    }
}
