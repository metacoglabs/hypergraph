// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

public record IncidentEdge(long edge, int roleSet, long locator, long validFrom, long validTo) {
}
