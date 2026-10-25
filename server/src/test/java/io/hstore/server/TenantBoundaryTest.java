// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantBoundaryTest {

    private static final Map<String, String> ENGINE_WIDE = Map.of(
            "io/hstore/server/studio/Studio.java", "the shared schema listing and the session's tenant name",
            "io/hstore/server/studio/Neighborhood.java", "the shared schema listing",
            "io/hstore/server/Main.java", "hstore init creates the first superuser before any session exists");
    private static final Pattern RAW = Pattern.compile("engineView\\(\\)|\\.transaction\\(\\)|engine\\(\\)\\.(snapshot|read|write)\\(");

    @Test
    void onlyEngineWideCodeReadsTheRawView() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> users;
        try (Stream<Path> files = Files.walk(root)) {
            users = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        try {
                            return RAW.matcher(Files.readString(file)).find();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(), users.stream().filter(file -> !ENGINE_WIDE.containsKey(file)).toList(),
                "server code must use the tenant-checked methods of Reader; engine-wide code belongs in ENGINE_WIDE with a reason");
        assertEquals(List.of(), ENGINE_WIDE.keySet().stream().filter(file -> !users.contains(file)).sorted().toList(),
                "these files no longer read the raw view; remove them from ENGINE_WIDE");
    }
}
