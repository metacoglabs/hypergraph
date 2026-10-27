// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.HStoreException;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class Scram {

    public static final String MECHANISM = "SCRAM-SHA-256";
    public static final String MECHANISM_PLUS = "SCRAM-SHA-256-PLUS";
    public static final String CHANNEL_BINDING = "tls-server-end-point";
    public static final String PREFIX = "SCRAM-SHA-256$";
    public static final int MIN_ITERATIONS = 4096;
    public static final int MAX_ITERATIONS = 10_000_000;
    private static final int NONCE_BYTES = 18;
    private static final int MAX_MESSAGE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODE = Base64.getEncoder();
    private static final Base64.Decoder DECODE = Base64.getDecoder();

    private Scram() {
    }

    public record Verifier(int iterations, byte[] salt, byte[] storedKey, byte[] serverKey) {

        public static Verifier of(String password, byte[] salt, int iterations) {
            return fromSaltedPassword(saltedPassword(SaslPrep.prepare(password), salt, iterations), salt, iterations);
        }

        public static Verifier fromSaltedPassword(byte[] saltedPassword, byte[] salt, int iterations) {
            return new Verifier(iterations, salt.clone(), sha256(hmac(saltedPassword, "Client Key")), hmac(saltedPassword, "Server Key"));
        }

        public static Optional<Verifier> parse(String text) {
            if (!text.startsWith(PREFIX)) {
                return Optional.empty();
            }
            String[] parts = text.substring(PREFIX.length()).split("\\$", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            String[] head = parts[0].split(":", -1);
            String[] keys = parts[1].split(":", -1);
            if (head.length != 2 || keys.length != 2) {
                return Optional.empty();
            }
            try {
                int iterations = Integer.parseInt(head[0]);
                byte[] salt = DECODE.decode(head[1]);
                byte[] stored = DECODE.decode(keys[0]);
                byte[] server = DECODE.decode(keys[1]);
                if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS || salt.length == 0 || stored.length != 32 || server.length != 32) {
                    return Optional.empty();
                }
                return Optional.of(new Verifier(iterations, salt, stored, server));
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }

        public String encode() {
            return PREFIX + iterations + ":" + ENCODE.encodeToString(salt) + "$" + ENCODE.encodeToString(storedKey) + ":"
                    + ENCODE.encodeToString(serverKey);
        }

        public boolean matches(String password) {
            byte[] salted = saltedPassword(SaslPrep.prepare(password), salt, iterations);
            return MessageDigest.isEqual(sha256(hmac(salted, "Client Key")), storedKey);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Verifier(int i, byte[] s, byte[] stored, byte[] server) && i == iterations
                    && Arrays.equals(s, salt) && Arrays.equals(stored, storedKey) && Arrays.equals(server, serverKey);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(storedKey) + iterations;
        }

        @Override
        public String toString() {
            return "Verifier[iterations=" + iterations + "]";
        }
    }

    public static byte[] saltedPassword(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", e);
        }
    }

    public static byte[] endPoint(X509Certificate certificate) {
        String algorithm = certificate.getSigAlgName().toUpperCase(Locale.ROOT);
        String digest = algorithm.contains("SHA512") ? "SHA-512" : algorithm.contains("SHA384") ? "SHA-384" : "SHA-256";
        try {
            return MessageDigest.getInstance(digest).digest(certificate.getEncoded());
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("cannot hash the server certificate", e);
        }
    }

    static byte[] hmac(byte[] key, String message) {
        return hmac(key, message.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] xor(byte[] left, byte[] right) {
        byte[] out = new byte[left.length];
        for (int i = 0; i < left.length; i++) {
            out[i] = (byte) (left[i] ^ right[i]);
        }
        return out;
    }

    static String nonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODE.encodeToString(bytes);
    }

    private static String saslName(String user) {
        return user.replace("=", "=3D").replace(",", "=2C");
    }

    private static String unsaslName(String name) {
        if (name.replaceAll("=3D|=2C", "").contains("=")) {
            throw malformed("invalid user name encoding");
        }
        return name.replace("=2C", ",").replace("=3D", "=");
    }

    private static Map<String, String> attributes(String message, int skip) {
        Map<String, String> found = new LinkedHashMap<>();
        String[] parts = message.split(",", -1);
        for (int i = skip; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() < 2 || part.charAt(1) != '=') {
                throw malformed("expected an attribute, found '" + part + "'");
            }
            found.putIfAbsent(part.substring(0, 1), part.substring(2));
        }
        return found;
    }

    private static HStoreException malformed(String detail) {
        return HStoreException.invalid("malformed SCRAM message: " + detail);
    }

    private static String required(Map<String, String> attributes, String name) {
        String value = attributes.get(name);
        if (value == null) {
            throw malformed("missing " + name + "=");
        }
        return value;
    }

    private static byte[] decode(String text, String name) {
        try {
            return DECODE.decode(text);
        } catch (IllegalArgumentException e) {
            throw malformed(name + " is not base64");
        }
    }

    public static final class Client {
        private final String user;
        private final String password;
        private final Optional<byte[]> channelBinding;
        private final String nonce;
        private String header;
        private String firstBare;
        private byte[] serverSignature;

        public Client(String user, String password, Optional<byte[]> channelBinding) {
            this(user, password, channelBinding, nonce());
        }

        Client(String user, String password, Optional<byte[]> channelBinding, String nonce) {
            this.user = user;
            this.password = password;
            this.channelBinding = channelBinding.map(byte[]::clone);
            this.nonce = nonce;
        }

        public String mechanism() {
            return channelBinding.isPresent() ? MECHANISM_PLUS : MECHANISM;
        }

        public String first() {
            header = channelBinding.isPresent() ? "p=" + CHANNEL_BINDING + ",," : "n,,";
            firstBare = "n=" + saslName(user) + ",r=" + nonce;
            return header + firstBare;
        }

        public String last(String serverFirst) {
            Map<String, String> fields = attributes(serverFirst, 0);
            if (fields.containsKey("m")) {
                throw malformed("unsupported extension");
            }
            String combined = required(fields, "r");
            if (!combined.startsWith(nonce) || combined.length() <= nonce.length()) {
                throw malformed("the server nonce does not extend the client nonce");
            }
            byte[] salt = decode(required(fields, "s"), "salt");
            int iterations;
            try {
                iterations = Integer.parseInt(required(fields, "i"));
            } catch (NumberFormatException e) {
                throw malformed("iteration count is not a number");
            }
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                throw malformed("iteration count " + iterations + " is outside " + MIN_ITERATIONS + " to " + MAX_ITERATIONS);
            }
            byte[] bindData = concat(header.getBytes(StandardCharsets.UTF_8), channelBinding.orElse(new byte[0]));
            String withoutProof = "c=" + ENCODE.encodeToString(bindData) + ",r=" + combined;
            String authMessage = firstBare + "," + serverFirst + "," + withoutProof;
            byte[] salted = saltedPassword(SaslPrep.prepare(password), salt, iterations);
            byte[] clientKey = hmac(salted, "Client Key");
            byte[] proof = xor(clientKey, hmac(sha256(clientKey), authMessage));
            serverSignature = hmac(hmac(salted, "Server Key"), authMessage);
            return withoutProof + ",p=" + ENCODE.encodeToString(proof);
        }

        public void verify(String serverFinal) {
            Map<String, String> fields = attributes(serverFinal, 0);
            if (fields.containsKey("e")) {
                throw HStoreException.invalid("the server rejected the sign-in: " + fields.get("e"));
            }
            if (serverSignature == null || !MessageDigest.isEqual(decode(required(fields, "v"), "verifier"), serverSignature)) {
                throw HStoreException.invalid("the server could not prove that it knows the password; it may not be the server you meant to reach");
            }
        }
    }

    public sealed interface Outcome {
        record Accepted(String user, String serverFinal) implements Outcome {
        }

        record Rejected(String user) implements Outcome {
        }
    }

    public static final class Server {
        private final Function<String, Optional<Verifier>> verifiers;
        private final byte[] mockKey;
        private final int mockIterations;
        private final Optional<byte[]> channelBinding;
        private final String serverNonce;
        private String user;
        private String header;
        private String firstBare;
        private String serverFirst;
        private String combinedNonce;
        private Optional<Verifier> verifier = Optional.empty();
        private boolean finished;

        public Server(Function<String, Optional<Verifier>> verifiers, byte[] mockKey, int mockIterations, Optional<byte[]> channelBinding) {
            this(verifiers, mockKey, mockIterations, channelBinding, nonce());
        }

        Server(Function<String, Optional<Verifier>> verifiers, byte[] mockKey, int mockIterations, Optional<byte[]> channelBinding, String serverNonce) {
            this.verifiers = verifiers;
            this.mockKey = mockKey.clone();
            this.mockIterations = mockIterations;
            this.channelBinding = channelBinding.map(byte[]::clone);
            this.serverNonce = serverNonce;
        }

        public String user() {
            if (user == null) {
                throw new IllegalStateException("no client-first message yet");
            }
            return user;
        }

        public String first(String mechanism, String clientFirst) {
            if (user != null) {
                throw malformed("the exchange already started");
            }
            if (clientFirst.length() > MAX_MESSAGE) {
                throw malformed("message too long");
            }
            boolean plus = switch (mechanism) {
                case MECHANISM -> false;
                case MECHANISM_PLUS -> true;
                default -> throw HStoreException.invalid("unsupported SASL mechanism '" + mechanism + "'; this server offers "
                        + MECHANISM + (channelBinding.isPresent() ? " and " + MECHANISM_PLUS : ""));
            };
            String[] parts = clientFirst.split(",", 3);
            if (parts.length != 3) {
                throw malformed("expected gs2 header and client-first-message-bare");
            }
            String flag = parts[0];
            if (!parts[1].isEmpty()) {
                throw malformed("authorization identities are not supported");
            }
            if (plus) {
                if (channelBinding.isEmpty()) {
                    throw HStoreException.invalid(MECHANISM_PLUS + " needs a TLS connection");
                }
                if (!flag.equals("p=" + CHANNEL_BINDING)) {
                    throw malformed("only " + CHANNEL_BINDING + " channel binding is supported");
                }
            } else if (flag.equals("y")) {
                if (channelBinding.isPresent()) {
                    throw HStoreException.invalid("the client did not use channel binding although this server supports it; "
                            + "the connection may have been tampered with");
                }
            } else if (!flag.equals("n")) {
                throw malformed(flag.startsWith("p=") ? "channel binding needs " + MECHANISM_PLUS : "invalid channel binding flag");
            }
            header = flag + ",,";
            firstBare = parts[2];
            Map<String, String> fields = attributes(firstBare, 0);
            if (fields.containsKey("m")) {
                throw malformed("unsupported extension");
            }
            user = unsaslName(required(fields, "n"));
            String clientNonce = required(fields, "r");
            if (clientNonce.isEmpty() || clientNonce.contains(",")) {
                throw malformed("invalid client nonce");
            }
            combinedNonce = clientNonce + serverNonce;
            verifier = verifiers.apply(user);
            int iterations = verifier.map(Verifier::iterations).orElse(mockIterations);
            byte[] salt = verifier.map(Verifier::salt).orElseGet(() -> Arrays.copyOf(hmac(mockKey, "salt:" + user), 16));
            serverFirst = "r=" + combinedNonce + ",s=" + ENCODE.encodeToString(salt) + ",i=" + iterations;
            return serverFirst;
        }

        public Outcome last(String clientFinal) {
            if (serverFirst == null || finished) {
                throw malformed("unexpected client-final message");
            }
            finished = true;
            if (clientFinal.length() > MAX_MESSAGE) {
                throw malformed("message too long");
            }
            int proofAt = clientFinal.lastIndexOf(",p=");
            if (proofAt < 0) {
                throw malformed("missing p=");
            }
            String withoutProof = clientFinal.substring(0, proofAt);
            Map<String, String> fields = attributes(withoutProof, 0);
            byte[] expectedBinding = concat(header.getBytes(StandardCharsets.UTF_8), header.startsWith("p=") ? channelBinding.orElseThrow() : new byte[0]);
            boolean bound = MessageDigest.isEqual(decode(required(fields, "c"), "channel binding"), expectedBinding);
            boolean nonceMatches = required(fields, "r").equals(combinedNonce);
            byte[] proof = decode(clientFinal.substring(proofAt + 3), "proof");
            String authMessage = firstBare + "," + serverFirst + "," + withoutProof;
            Verifier known = verifier.orElseGet(() -> new Verifier(mockIterations, new byte[16], hmac(mockKey, "stored:" + user), hmac(mockKey, "server:" + user)));
            byte[] signature = hmac(known.storedKey(), authMessage);
            boolean proven = proof.length == 32 && MessageDigest.isEqual(sha256(xor(proof, signature)), known.storedKey());
            if (verifier.isEmpty() || !bound || !nonceMatches || !proven) {
                return new Outcome.Rejected(user);
            }
            return new Outcome.Accepted(user, "v=" + ENCODE.encodeToString(hmac(known.serverKey(), authMessage)));
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    public static Optional<byte[]> endPointOf(Certificate[] certificates) {
        if (certificates == null || certificates.length == 0 || !(certificates[0] instanceof X509Certificate leaf)) {
            return Optional.empty();
        }
        try {
            leaf.getEncoded();
        } catch (CertificateEncodingException e) {
            return Optional.empty();
        }
        return Optional.of(endPoint(leaf));
    }
}
