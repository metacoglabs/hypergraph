package io.hstore.db.query;

import io.hstore.db.Atom;
import io.hstore.db.Reader;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.value.Value;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeAlgebra;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

public sealed interface AccessPath {

    String kind();

    String describe();

    LongStream scan(Reader reader);

    record AllAtoms(boolean edges) implements AccessPath {
        public String kind() {
            return "catalog-scan";
        }

        public String describe() {
            return "CatalogScan(" + (edges ? "edges" : "nodes") + ")";
        }

        public LongStream scan(Reader reader) {
            return reader.allAtoms(edges).mapToLong(Atom::id);
        }
    }

    record TypeScan(TypeDef type) implements AccessPath {
        public String kind() {
            return "type-scan";
        }

        public String describe() {
            return "TypeScan(" + type.name() + ")";
        }

        public LongStream scan(Reader reader) {
            return reader.atomsOfType(type.id());
        }
    }

    record Incident(List<Long> atoms) implements AccessPath {
        public String kind() {
            return "incident";
        }

        public String describe() {
            return "IncidentIntersection" + atoms.stream().map(atom -> "@" + atom).toList();
        }

        public LongStream scan(Reader reader) {
            List<Tree<?>> postings = atoms.stream().<Tree<?>>map(atom -> reader.view().incidentTree(atom)).toList();
            return TreeAlgebra.intersectKeys(postings);
        }
    }

    record Members(long edge) implements AccessPath {
        public String kind() {
            return "members";
        }

        public String describe() {
            return "MemberScan(@" + edge + ")";
        }

        public LongStream scan(Reader reader) {
            return reader.view().edge(edge).map(found -> found.membership().keys()).orElseGet(LongStream::empty);
        }
    }

    record IndexRange(TypeDef type, String property, Optional<Value> low, Optional<Value> high,
                      boolean lowInclusive, boolean highInclusive) implements AccessPath {
        public String kind() {
            return "property-index";
        }

        public String describe() {
            return "IndexRange(" + type.name() + "." + property + " " + (lowInclusive ? "[" : "(")
                    + low.map(Value::render).orElse("-inf") + ", " + high.map(Value::render).orElse("+inf") + (highInclusive ? "]" : ")") + ")";
        }

        public LongStream scan(Reader reader) {
            return reader.range(type.name(), property, low, high, lowInclusive, highInclusive);
        }
    }

    record Semantic(String text, int k, SemanticPlane.Consistency consistency) implements AccessPath {
        public String kind() {
            return "semantic";
        }

        public String describe() {
            return "SemanticCandidates(top " + k + " for '" + text + "', " + consistency + ")";
        }

        public LongStream scan(Reader reader) {
            return reader.similar(text, k, consistency).stream().mapToLong(SemanticPlane.Hit::atom);
        }
    }

    record Fixed(long atom) implements AccessPath {
        public String kind() {
            return "fixed";
        }

        public String describe() {
            return "Lookup(@" + atom + ")";
        }

        public LongStream scan(Reader reader) {
            return reader.visible(atom) ? LongStream.of(atom) : LongStream.empty();
        }
    }

    record Intersection(List<AccessPath> parts) implements AccessPath {
        public String kind() {
            return "intersection";
        }

        public String describe() {
            return "Intersect" + parts.stream().map(AccessPath::describe).toList();
        }

        public LongStream scan(Reader reader) {
            long[] result = null;
            for (AccessPath part : parts) {
                long[] ids = part.scan(reader).sorted().distinct().toArray();
                result = result == null ? ids : intersect(result, ids);
                if (result.length == 0) {
                    break;
                }
            }
            return LongStream.of(result == null ? new long[0] : result);
        }

        private static long[] intersect(long[] left, long[] right) {
            long[] out = new long[Math.min(left.length, right.length)];
            int i = 0;
            int j = 0;
            int n = 0;
            while (i < left.length && j < right.length) {
                if (left[i] < right[j]) {
                    i++;
                } else if (left[i] > right[j]) {
                    j++;
                } else {
                    out[n++] = left[i];
                    i++;
                    j++;
                }
            }
            return Arrays.copyOf(out, n);
        }
    }

    record Union(List<AccessPath> parts) implements AccessPath {
        public String kind() {
            return "union";
        }

        public String describe() {
            return "Union" + parts.stream().map(AccessPath::describe).toList();
        }

        public LongStream scan(Reader reader) {
            return parts.stream().flatMapToLong(part -> part.scan(reader)).sorted().distinct();
        }
    }
}
