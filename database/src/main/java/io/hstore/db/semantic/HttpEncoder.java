// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.semantic;

import io.hstore.db.value.Json;
import io.hstore.db.value.Value;
import io.hstore.engine.HStoreException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class HttpEncoder implements Encoder {

    public enum Protocol { OPENAI, OLLAMA }

    private static final int ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final URI endpoint;
    private final String model;
    private final int dimensions;
    private final Optional<String> apiKey;
    private final Protocol protocol;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public HttpEncoder(URI endpoint, String model, int dimensions, Optional<String> apiKey, Protocol protocol) {
        this.endpoint = endpoint;
        this.model = model;
        this.dimensions = dimensions;
        this.apiKey = apiKey;
        this.protocol = protocol;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public Encoded encode(String input) {
        Map<String, Json> body = new LinkedHashMap<>();
        body.put("model", new Json.Str(model));
        body.put(protocol == Protocol.OPENAI ? "input" : "prompt", new Json.Str(input));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Json.Obj(body).print()));
        apiKey.ifPresent(key -> request.header("Authorization", "Bearer " + key));
        HStoreException failure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return new Encoded(vector(Json.parse(response.body())), model, version());
                }
                failure = HStoreException.io("embedding service answered HTTP " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(200, response.body().length())), null);
                if (response.statusCode() / 100 == 4 && response.statusCode() != 429) {
                    throw failure;
                }
            } catch (IOException e) {
                failure = HStoreException.io("embedding service " + endpoint + " is unreachable", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw HStoreException.io("interrupted while calling the embedding service", e);
            }
            pause(attempt);
        }
        throw failure;
    }

    private float[] vector(Json response) {
        String path = protocol == Protocol.OPENAI ? "$.data[0].embedding[*]" : "$.embedding[*]";
        List<Value> components = response.select(path).toList();
        if (components.size() != dimensions) {
            throw HStoreException.invalid("embedding service returned " + components.size() + " dimensions, expected " + dimensions);
        }
        float[] vector = new float[components.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) components.get(i).number().orElseThrow(() -> HStoreException.invalid("non-numeric embedding component"));
        }
        return vector;
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(200L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
