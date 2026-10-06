// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

public final class Tls {

    public record Client(SSLContext context, boolean verifyHostname) {

        public SSLSocket connect(String host, int port, int timeoutMillis) throws IOException {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), timeoutMillis);
            plain.setSoTimeout(timeoutMillis);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
            if (verifyHostname) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
            }
            socket.startHandshake();
            return socket;
        }
    }

    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN PRIVATE KEY-----([^-]+)-----END PRIVATE KEY-----");
    private static final char[] NO_PASSWORD = new char[0];

    private Tls() {
    }

    public static SSLContext server(Path certificateFile, Path keyFile) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            List<Certificate> chain = certificates(certificateFile);
            store.setKeyEntry("hstore", privateKey(keyFile), NO_PASSWORD, chain.toArray(Certificate[]::new));
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, NO_PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot load the TLS certificate " + certificateFile + " and key " + keyFile + ": " + e.getMessage(), e);
        }
    }

    public static SSLContext trusting(Path certificateFile) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            List<Certificate> trusted = certificates(certificateFile);
            for (int i = 0; i < trusted.size(); i++) {
                store.setCertificateEntry("trusted-" + i, trusted.get(i));
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot load trusted certificates from " + certificateFile + ": " + e.getMessage(), e);
        }
    }

    public static SSLContext system() {
        try {
            return SSLContext.getDefault();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the platform has no default TLS context", e);
        }
    }

    private static List<Certificate> certificates(Path file) throws GeneralSecurityException, IOException {
        List<Certificate> certificates = List.copyOf(CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(Files.readAllBytes(file))));
        if (certificates.isEmpty()) {
            throw new GeneralSecurityException("no certificates in " + file);
        }
        return certificates;
    }

    private static PrivateKey privateKey(Path file) throws GeneralSecurityException {
        String pem = read(file);
        Matcher matcher = PRIVATE_KEY.matcher(pem);
        if (!matcher.find()) {
            throw new GeneralSecurityException(file + " must hold an unencrypted PKCS#8 key (BEGIN PRIVATE KEY); convert it with "
                    + "openssl pkcs8 -topk8 -nocrypt");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(matcher.group(1)));
        GeneralSecurityException last = null;
        for (String algorithm : List.of("EC", "RSA", "Ed25519")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException wrongAlgorithm) {
                last = wrongAlgorithm;
            }
        }
        throw last;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
