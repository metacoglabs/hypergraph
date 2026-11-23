// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.index.IndexValues;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;

public final class SignatureSlots {

    public record SignatureName(int tenant, Signature signature, long edgeCount) {
    }

    static final TreeSchema<Signature> SIGNATURE_SCHEMA = new TreeSchema<>(84, "edge-signature", FingerprintMode.SET,
            ValueCodec.rows(Signature::encodedSize, (out, signature) -> signature.writeTo(out), Signature::readFrom),
            EntryMeasure.keyed(signature -> Hashing.of(signature.canonicalBytes())));
    static final PostingIndex<Marker> SIGNATURE_EDGES =
            PostingIndex.of(85, 86, "signature-edges", IndexValues.MARKER, _ -> 1, 32);
    static final TreeSchema<List<SignatureName>> NAME_SCHEMA = new TreeSchema<>(87, "signature-names", FingerprintMode.SET,
            ValueCodec.rows(SignatureSlots::namesSize, SignatureSlots::writeNames, SignatureSlots::readNames),
            EntryMeasure.keyed(SignatureSlots::namesHash));

    public static final Slot<Signature> SIGNATURES = Slot.derived(47, "edge-signature", SIGNATURE_SCHEMA);
    public static final Slot<Postings<Marker>> EDGES = Slot.derived(48, "signature-edges", SIGNATURE_EDGES.schema());
    public static final Slot<List<SignatureName>> NAMES = Slot.derived(49, "signature-names", NAME_SCHEMA);
    public static final List<Slot<?>> SLOTS = List.of(SIGNATURES, EDGES, NAMES);

    private SignatureSlots() {
    }

    public static long signatureKey(int tenant, Signature signature) {
        return Hashing.of(tenant, signature.edgeType(), Hashing.of(signature.canonicalBytes()));
    }

    public static List<SignatureName> withName(List<SignatureName> names, SignatureName name) {
        List<SignatureName> next = new ArrayList<>(names.stream()
                .filter(found -> found.tenant() != name.tenant() || !found.signature().equals(name.signature()))
                .toList());
        if (name.edgeCount() > 0) {
            next.add(name);
        }
        return List.copyOf(next);
    }

    private static int namesSize(List<SignatureName> names) {
        return ByteCursor.varLongSize(names.size()) + names.stream()
                .mapToInt(name -> ByteCursor.varLongSize(name.tenant()) + name.signature().encodedSize() + ByteCursor.varLongSize(name.edgeCount()))
                .sum();
    }

    private static void writeNames(ByteCursor out, List<SignatureName> names) {
        out.putVarInt(names.size());
        names.forEach(name -> {
            out.putVarInt(name.tenant());
            name.signature().writeTo(out);
            out.putVarLong(name.edgeCount());
        });
    }

    private static List<SignatureName> readNames(ByteCursor in) {
        int n = in.getVarInt();
        List<SignatureName> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add(new SignatureName(in.getVarInt(), Signature.readFrom(in), in.getVarLong()));
        }
        return List.copyOf(names);
    }

    private static long namesHash(List<SignatureName> names) {
        long hash = 0;
        for (SignatureName name : names) {
            hash = Hashing.combine(hash, Hashing.of(name.tenant(), Hashing.of(name.signature().canonicalBytes()), name.edgeCount()));
        }
        return hash;
    }
}
