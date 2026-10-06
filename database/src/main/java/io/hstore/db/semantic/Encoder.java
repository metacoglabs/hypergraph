// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.semantic;

import io.hstore.engine.tree.Hashing;

import java.util.Locale;

public interface Encoder {

    record Encoded(float[] vector, String model, int version) {
    }

    String model();

    int version();

    int dimensions();

    Encoded encode(String input);

    static Encoder hashing(int dimensions) {
        return new Encoder() {
            @Override
            public String model() {
                return "hashing-" + dimensions;
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public Encoded encode(String input) {
                float[] vector = new float[dimensions];
                String text = input.toLowerCase(Locale.ROOT);
                for (String token : text.split("[^\\p{L}\\p{N}]+")) {
                    if (token.isEmpty()) {
                        continue;
                    }
                    accumulate(vector, Hashing.of("w:" + token), 1.0f);
                    String padded = "^" + token + "$";
                    for (int i = 0; i + 3 <= padded.length(); i++) {
                        accumulate(vector, Hashing.of("g:" + padded.substring(i, i + 3)), 0.5f);
                    }
                }
                return new Encoded(Embedding.normalized(vector), model(), version());
            }

            private void accumulate(float[] vector, long hash, float weight) {
                int slot = (int) Math.floorMod(hash, (long) dimensions);
                vector[slot] += (hash >>> 63) == 0 ? weight : -weight;
            }
        };
    }
}
