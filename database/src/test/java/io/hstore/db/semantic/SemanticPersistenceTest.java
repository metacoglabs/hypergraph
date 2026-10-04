package io.hstore.db.semantic;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.schema.TypeDef;
import io.hstore.engine.EngineOptions;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticPersistenceTest {

    @TempDir
    Path directory;

    private static DatabaseOptions options() {
        return DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
    }

    @Test
    void indexSurvivesRestartsWithoutRebuilding() {
        long generation;
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options())) {
            database.write(writer -> {
                writer.defineNode("Doc", List.<TypeDef.PropertyDef>of());
                return null;
            });
            database.write(writer -> {
                writer.embed(writer.node("Doc", "a", Map.of()), "hypergraph storage engine pages");
                writer.embed(writer.node("Doc", "b", Map.of()), "image segmentation networks");
                return null;
            });
            database.read(reader -> reader.similar("storage engine", 1, SemanticPlane.Consistency.FRESH));
            generation = database.semantic().indexedGeneration();
        }
        assertTrue(Files.exists(directory.resolve("semantic").resolve("state")));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options())) {
            assertTrue(database.semantic().indexedGeneration() >= generation);
            long hit = database.read(reader -> reader.similar("storage engine", 1, SemanticPlane.Consistency.FRESH).getFirst().atom());
            long expected = database.read(reader -> reader.resolve("Doc", "a"));
            assertEquals(expected, hit);
        }
    }

    @Test
    void httpEncoderSpeaksTheOpenAiEmbeddingProtocol() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = (request.contains("\"input\":\"hello\"")
                    ? "{\"data\":[{\"embedding\":[0.5,0.25,-1]}]}" : "{\"error\":\"bad\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Encoder encoder = new HttpEncoder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings"),
                    "test-model", 3, Optional.of("key"), HttpEncoder.Protocol.OPENAI);
            assertArrayEquals(new float[]{0.5f, 0.25f, -1f}, encoder.encode("hello").vector());
        } finally {
            server.stop(0);
        }
    }
}
