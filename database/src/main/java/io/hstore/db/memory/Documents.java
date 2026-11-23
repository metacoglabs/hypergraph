// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.Schema;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.security.Quota.Usage;
import io.hstore.db.security.Security;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public final class Documents {

    public static final String REVISION_TYPE = "$Revision";
    public static final String SECTION_TYPE = "$Section";
    public static final int MAX_BYTES = 16 << 20;
    public static final int MAX_SECTIONS = 10_000;
    public static final int MAX_SOURCES = 16;
    public static final int MAX_NOTE = 200;
    public static final int TITLE_BYTES = 120;

    public record Types(int revision, int section) {
        public static final Types NONE = new Types(-1, -1);

        public boolean contains(int type) {
            return type == revision || type == section;
        }
    }

    public record Purged(int revisions, int sections) {
    }

    private Documents() {
    }

    public static Types types(View view, Schema schema) {
        Optional<TypeDef> revision = schema.type(view, REVISION_TYPE);
        Optional<TypeDef> section = schema.type(view, SECTION_TYPE);
        return revision.isPresent() && section.isPresent() ? new Types(revision.get().id(), section.get().id()) : Types.NONE;
    }

    public static Types define(Transaction txn, Schema schema) {
        return new Types(schema.defineSystem(txn, REVISION_TYPE, AtomKind.NODE).id(), schema.defineSystem(txn, SECTION_TYPE, AtomKind.NODE).id());
    }

    public static String revisionKey(long document, int number) {
        return document + "@" + number;
    }

    public static String sectionKey(long document, int number, int ordinal) {
        return revisionKey(document, number) + "#" + ordinal;
    }

    public static OptionalLong owner(AtomRecord record) {
        if (!(record instanceof AtomRecord.NodeRecord node)) {
            return OptionalLong.empty();
        }
        int at = node.canonicalKey().indexOf('@');
        try {
            return at > 0 ? OptionalLong.of(Long.parseLong(node.canonicalKey().substring(0, at))) : OptionalLong.empty();
        } catch (NumberFormatException malformed) {
            return OptionalLong.empty();
        }
    }

    public static void checkSpec(RevisionSpec spec) {
        if (spec.sources().size() > MAX_SOURCES) {
            throw HStoreException.invalid("a revision names at most " + MAX_SOURCES + " sources, not " + spec.sources().size());
        }
        if (spec.note().length() > MAX_NOTE) {
            throw HStoreException.invalid("a revision note has at most " + MAX_NOTE + " characters");
        }
        if (spec.validFrom().isPresent() && spec.validFrom().getAsLong() >= spec.validTo()) {
            throw HStoreException.invalid("a revision must be valid for some time: " + spec.validFrom().getAsLong() + " is not before " + spec.validTo());
        }
    }

    public static Revision append(Transaction txn, Types types, long document, int tenant, String author, String markdown, byte[] bytes,
                                  long payload, RevisionSpec spec) {
        checkSpec(spec);
        List<Markdown.Part> parts = Markdown.sections(markdown);
        if (parts.size() > MAX_SECTIONS) {
            throw HStoreException.limit("a revision has at most " + MAX_SECTIONS + " sections, this one has " + parts.size());
        }
        Optional<DocumentHead> head = txn.get(MemorySlots.DOCUMENTS, document);
        Optional<Revision> previous = head.flatMap(found -> txn.get(MemorySlots.REVISIONS, found.latest()));
        int number = head.map(found -> found.revisions() + 1).orElse(1);
        long recordedAt = Math.max(System.currentTimeMillis(), previous.map(found -> found.recordedAt() + 1).orElse(Long.MIN_VALUE));
        long validFrom = spec.validFrom().orElse(recordedAt);
        if (validFrom >= spec.validTo()) {
            throw HStoreException.invalid("a revision must be valid for some time: " + validFrom + " is not before " + spec.validTo());
        }
        Security.charge(txn, tenant, new Usage(1 + parts.size(), 0, 0));
        long id = txn.createNode(new AtomRecord.NodeRecord(types.revision(), revisionKey(document, number), 0, 0, 0, tenant, 0));
        for (Markdown.Part part : parts) {
            long section = txn.createNode(new AtomRecord.NodeRecord(types.section(), sectionKey(document, number, part.ordinal()), 0, 0, 0, tenant, 0));
            String title = part.path().isEmpty() ? "" : truncate(part.path().getLast());
            txn.put(MemorySlots.SECTIONS, section, new Section(section, document, id, part.ordinal(), part.parent(), part.level(), title,
                    part.anchor(), part.start(), part.end(), digest(bytes, part.start(), part.end()), tenant));
        }
        Revision revision = new Revision(id, document, number, previous.map(Revision::id).orElse(0L), payload, bytes.length,
                digest(bytes, 0, bytes.length), validFrom, spec.validTo(), recordedAt, author, spec.note(), spec.sources(), parts.size(), tenant);
        txn.put(MemorySlots.REVISIONS, id, revision);
        txn.merge(MemorySlots.DOCUMENTS, document, current -> current.equals(head), _ -> Optional.of(new DocumentHead(number, id, tenant)));
        return revision;
    }

    public static Optional<DocumentHead> head(View view, int tenant, long document) {
        return view.get(MemorySlots.DOCUMENTS, document).filter(found -> found.tenant() == tenant);
    }

    public static Optional<Revision> revision(View view, Types types, int tenant, long document, int number) {
        OptionalLong id = view.resolve(tenant, types.revision(), revisionKey(document, number));
        return id.isEmpty() ? Optional.empty() : view.get(MemorySlots.REVISIONS, id.getAsLong()).filter(found -> found.tenant() == tenant);
    }

    public static List<Revision> revisions(View view, Types types, int tenant, long document) {
        int count = head(view, tenant, document).map(DocumentHead::revisions).orElse(0);
        List<Revision> revisions = new ArrayList<>(count);
        for (int number = 1; number <= count; number++) {
            revision(view, types, tenant, document, number).ifPresent(revisions::add);
        }
        return revisions;
    }

    public static Optional<Section> section(View view, Types types, int tenant, long document, int number, int ordinal) {
        OptionalLong id = view.resolve(tenant, types.section(), sectionKey(document, number, ordinal));
        return id.isEmpty() ? Optional.empty() : view.get(MemorySlots.SECTIONS, id.getAsLong()).filter(found -> found.tenant() == tenant);
    }

    public static List<Section> sections(View view, Types types, int tenant, Revision revision) {
        List<Section> sections = new ArrayList<>(revision.sections());
        for (int ordinal = 0; ordinal < revision.sections(); ordinal++) {
            section(view, types, tenant, revision.document(), revision.number(), ordinal).ifPresent(sections::add);
        }
        return sections;
    }

    public static List<String> path(View view, Types types, int tenant, Section section) {
        Optional<Revision> revision = view.get(MemorySlots.REVISIONS, section.revision());
        List<String> path = new ArrayList<>();
        Optional<Section> current = Optional.of(section);
        while (current.isPresent() && current.get().level() > 0) {
            path.addFirst(current.get().title());
            int parent = current.get().parent();
            current = parent < 0 || revision.isEmpty() ? Optional.empty()
                    : section(view, types, tenant, section.document(), revision.get().number(), parent);
        }
        return path;
    }

    public static Optional<Revision> at(List<Revision> revisions, long validTime, long recordedAsOf) {
        return revisions.stream()
                .filter(revision -> revision.recordedAt() <= recordedAsOf && revision.validAt(validTime))
                .max(Comparator.comparingLong(Revision::validFrom).thenComparingInt(Revision::number));
    }

    static String truncate(String title) {
        byte[] bytes = title.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= TITLE_BYTES) {
            return title;
        }
        int end = title.length();
        while (title.substring(0, end).getBytes(StandardCharsets.UTF_8).length > TITLE_BYTES) {
            end = title.offsetByCodePoints(end, -1);
        }
        return title.substring(0, end);
    }

    public static long digest(byte[] bytes, int from, int to) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(bytes, from, to - from);
            byte[] hash = sha.digest();
            long value = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (hash[i] & 0xff);
            }
            return value;
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
