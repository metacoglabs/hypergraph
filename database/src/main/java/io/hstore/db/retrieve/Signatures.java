// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.retrieve.SignatureSlots.SignatureName;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.txn.View;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

public final class Signatures {

    private Signatures() {
    }

    public static Optional<Signature> of(View view, long edge) {
        return view.get(SignatureSlots.SIGNATURES, edge);
    }

    public static LongStream edges(View view, int tenant, Signature signature) {
        long key = SignatureSlots.signatureKey(tenant, signature);
        LongStream postings = view.get(SignatureSlots.EDGES, key).map(found -> postings(view, found)).orElse(LongStream.empty());
        if (!collides(view, tenant, signature)) {
            return postings;
        }
        return postings.filter(edge -> of(view, edge).filter(signature::equals).isPresent()
                && view.atom(edge).map(record -> record.tenant() == tenant).orElse(false));
    }

    public static long count(View view, int tenant, Signature signature) {
        return names(view, tenant, signature).stream()
                .filter(name -> name.tenant() == tenant && name.signature().equals(signature))
                .mapToLong(SignatureName::edgeCount).findFirst().orElse(0);
    }

    public static boolean collides(View view, int tenant, Signature signature) {
        return names(view, tenant, signature).size() > 1;
    }

    private static List<SignatureName> names(View view, int tenant, Signature signature) {
        return view.get(SignatureSlots.NAMES, SignatureSlots.signatureKey(tenant, signature)).orElse(List.of());
    }

    private static LongStream postings(View view, Postings<Marker> found) {
        return switch (found) {
            case Postings.Inline<Marker> inline -> Arrays.stream(inline.keys());
            case Postings.Promoted<Marker>(var root) -> new Tree<>(SignatureSlots.SIGNATURE_EDGES.postingSchema(), view.source(), root).keys();
        };
    }
}
