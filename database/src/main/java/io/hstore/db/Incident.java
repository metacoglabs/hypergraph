package io.hstore.db;

import java.util.List;

public record Incident(long edge, List<String> roles, long locator) {
}
