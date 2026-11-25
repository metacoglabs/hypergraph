// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.TreeSchema;

import java.util.List;

public final class MemorySlots {

    public static final Slot<Revision> REVISIONS = Slot.primary(57, "revisions",
            new TreeSchema<>(113, "revisions", FingerprintMode.SET, Revision.CODEC, EntryMeasure.keyed(Revision::hash)));

    public static final Slot<Section> SECTIONS = Slot.primary(58, "sections",
            new TreeSchema<>(114, "sections", FingerprintMode.SET, Section.CODEC, EntryMeasure.keyed(Section::hash)));

    public static final Slot<DocumentHead> DOCUMENTS = Slot.primary(59, "documents",
            new TreeSchema<>(115, "documents", FingerprintMode.SET, DocumentHead.CODEC, EntryMeasure.keyed(DocumentHead::hash)));

    public static final Slot<Erasure> ERASURES = Slot.primary(67, "erasures",
            new TreeSchema<>(116, "erasures", FingerprintMode.SET, Erasure.CODEC, EntryMeasure.keyed(Erasure::hash)));

    public static final List<Slot<?>> SLOTS = List.of(REVISIONS, SECTIONS, DOCUMENTS, ERASURES);

    private MemorySlots() {
    }
}
