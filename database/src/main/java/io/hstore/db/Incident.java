// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import java.util.List;

public record Incident(long edge, List<String> roles, long locator) {
}
