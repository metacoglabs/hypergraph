// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server.studio;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class Assets {

    record Asset(String contentType, byte[] body) {
    }

    private static final Pattern NAME = Pattern.compile("(?:[a-z0-9-]+/){0,3}[a-z0-9-]+\\.(?:html|css|js|svg)");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml");

    private final Map<String, Optional<Asset>> cache = new ConcurrentHashMap<>();
    private final Optional<Path> directory;

    Assets(Optional<Path> directory) {
        this.directory = directory;
    }

    Optional<Asset> find(String path) {
        String name = path.equals("/") || path.isEmpty() ? "index.html" : path.substring(1);
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return directory.isPresent() ? load(name) : cache.computeIfAbsent(name, this::load);
    }

    private Optional<Asset> load(String name) {
        try (InputStream in = open(name)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new Asset(TYPES.get(name.substring(name.lastIndexOf('.') + 1)), in.readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private InputStream open(String name) throws IOException {
        if (directory.isEmpty()) {
            return Assets.class.getResourceAsStream("/studio/" + name);
        }
        Path file = directory.get().resolve(name);
        return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
    }
}
