// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.index;

import io.hstore.engine.tree.TreeCursor;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

public sealed interface PostingCursor<P> permits PostingCursor.Inline, PostingCursor.Promoted {

    boolean seek(long target);

    long key();

    P value();

    boolean accepted();

    long size();

    static LongStream intersect(List<? extends PostingCursor<?>> cursors) {
        if (cursors.isEmpty()) {
            return LongStream.empty();
        }
        List<? extends PostingCursor<?>> ordered = cursors.stream().sorted(Comparator.comparingLong(PostingCursor::size)).toList();
        return StreamSupport.longStream(Spliterators.spliteratorUnknownSize(new Leapfrog(ordered),
                Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.NONNULL), false);
    }

    final class Inline<P> implements PostingCursor<P> {
        private final long[] keys;
        private final List<P> values;
        private final Predicate<? super P> accept;
        private int position;

        Inline(long[] keys, List<P> values, Predicate<? super P> accept) {
            this.keys = keys;
            this.values = values;
            this.accept = accept;
        }

        @Override
        public boolean seek(long target) {
            if (position < keys.length && keys[position] < target) {
                if (keys[position] + 1 == target) {
                    position++;
                } else {
                    int found = Arrays.binarySearch(keys, position, keys.length, target);
                    position = found >= 0 ? found : -found - 1;
                }
            }
            return position < keys.length;
        }

        @Override
        public boolean accepted() {
            return accept == null || accept.test(value());
        }

        @Override
        public long key() {
            if (position >= keys.length) {
                throw new NoSuchElementException();
            }
            return keys[position];
        }

        @Override
        public P value() {
            if (position >= keys.length) {
                throw new NoSuchElementException();
            }
            return values.get(position);
        }

        @Override
        public long size() {
            return keys.length;
        }
    }

    final class Promoted<P> implements PostingCursor<P> {
        private final TreeCursor<P> cursor;
        private final Predicate<? super P> accept;
        private final long size;

        Promoted(TreeCursor<P> cursor, Predicate<? super P> accept, long size) {
            this.cursor = cursor;
            this.accept = accept;
            this.size = size;
        }

        @Override
        public boolean seek(long target) {
            if (!cursor.hasNext()) {
                return false;
            }
            long key = cursor.key();
            if (key < target && key + 1 == target) {
                cursor.advance();
            } else if (key < target && !cursor.seek(target)) {
                return false;
            }
            return cursor.hasNext();
        }

        @Override
        public boolean accepted() {
            return accept == null || accept.test(cursor.value());
        }

        @Override
        public long key() {
            return cursor.key();
        }

        @Override
        public P value() {
            return cursor.value();
        }

        @Override
        public long size() {
            return size;
        }
    }

    final class Leapfrog implements PrimitiveIterator.OfLong {
        private final List<? extends PostingCursor<?>> cursors;
        private boolean ready;
        private boolean done;
        private long current;
        private long target = Long.MIN_VALUE;

        private Leapfrog(List<? extends PostingCursor<?>> cursors) {
            this.cursors = cursors;
        }

        @Override
        public boolean hasNext() {
            if (!ready && !done) {
                search();
            }
            return ready;
        }

        @Override
        public long nextLong() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ready = false;
            if (current == Long.MAX_VALUE) {
                done = true;
            } else {
                target = current + 1;
            }
            return current;
        }

        private void search() {
            int agreeing = 0;
            int index = 0;
            long candidate = target;
            while (true) {
                PostingCursor<?> cursor = cursors.get(index);
                if (!cursor.seek(candidate)) {
                    done = true;
                    return;
                }
                long key = cursor.key();
                if (key == candidate) {
                    agreeing++;
                } else {
                    candidate = key;
                    agreeing = 1;
                }
                if (agreeing == cursors.size()) {
                    if (allAccepted()) {
                        current = candidate;
                        ready = true;
                        return;
                    }
                    if (candidate == Long.MAX_VALUE) {
                        done = true;
                        return;
                    }
                    candidate++;
                    agreeing = 0;
                }
                index = (index + 1) % cursors.size();
            }
        }

        private boolean allAccepted() {
            for (PostingCursor<?> cursor : cursors) {
                if (!cursor.accepted()) {
                    return false;
                }
            }
            return true;
        }
    }
}
