// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.evidence;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public record EvidencePolicy(Set<AssertionType> accepted, double minimumConfidence) {

    public static final EvidencePolicy OBSERVED = new EvidencePolicy(EnumSet.of(AssertionType.OBSERVED), 0);
    public static final EvidencePolicy ANY = new EvidencePolicy(EnumSet.allOf(AssertionType.class), 0);

    public EvidencePolicy {
        accepted = Set.copyOf(accepted);
    }

    public static EvidencePolicy supported(double confidence) {
        return new EvidencePolicy(EnumSet.complementOf(EnumSet.of(AssertionType.REJECTED)), confidence);
    }

    public boolean admits(Optional<Qualifier> qualifier) {
        Qualifier effective = qualifier.orElse(Qualifier.OBSERVED);
        return accepted.contains(effective.type()) && effective.confidence() >= minimumConfidence;
    }
}
