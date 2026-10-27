// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeServerTest {

    private static final String REQUIRED = "hstore connection 1 at generation 2; authentication required";
    private static final RemoteEndpoint.Credentials ADMIN = new RemoteEndpoint.Credentials("admin", "pw-secret");

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String challenge(String clientFirst) {
        String message = new String(Base64.getDecoder().decode(clientFirst.substring(clientFirst.lastIndexOf(' ') + 1)), StandardCharsets.UTF_8);
        String nonce = message.substring(message.indexOf("r=") + 2);
        return "sasl " + base64("r=" + nonce + "fake,s=" + base64("salt-salt") + ",i=4096");
    }

    private static String forgedProof() {
        return "authenticated as admin\nsasl " + base64("v=" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    private record Fake(InetSocketAddress address, CompletableFuture<Boolean> clientHungUp) {
    }

    private static Fake fake(String banner, UnaryOperator<String> replies) throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        CompletableFuture<Boolean> hungUp = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (listener; Socket socket = listener.accept()) {
                socket.setSoTimeout(10_000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                WireProtocol.respond(out, banner);
                for (Optional<String> request = WireProtocol.receive(in, 1 << 20); request.isPresent(); request = WireProtocol.receive(in, 1 << 20)) {
                    WireProtocol.respond(out, replies.apply(request.get()));
                }
                hungUp.complete(true);
            } catch (Exception e) {
                hungUp.complete(e instanceof java.net.SocketException);
            }
        });
        return new Fake(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), hungUp);
    }

    private static void refused(String banner, UnaryOperator<String> replies, String reason) throws Exception {
        Fake fake = fake(banner, replies);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new RemoteEndpoint(fake.address(), Optional.of(ADMIN)));
        assertTrue(refused.getMessage().contains(reason), refused.getMessage());
        assertEquals(true, fake.clientHungUp().get(10, TimeUnit.SECONDS));
    }

    @Test
    void aServerThatClaimsSuccessWithoutScramIsRefused() throws Exception {
        refused(REQUIRED, _ -> "authenticated as admin", "did not answer with a SCRAM challenge");
    }

    @Test
    void aServerThatDoesNotAskForThePasswordIsRefused() throws Exception {
        refused("hstore connection 1 at generation 2", _ -> "authenticated as admin", "did not ask for a password");
    }

    @Test
    void aServerThatCannotProveItKnowsThePasswordIsRefused() throws Exception {
        refused(REQUIRED, request -> request.startsWith("SASL SCRAM") ? challenge(request) : forgedProof(),
                "could not prove that it knows the password");
    }

    @Test
    void aServerThatSkipsItsProofIsRefused() throws Exception {
        refused(REQUIRED, request -> request.startsWith("SASL SCRAM") ? challenge(request) : "authenticated as admin",
                "did not prove that it knows the password");
    }

    @Test
    void aServerThatNamesAnotherUserIsRefused() throws Exception {
        refused(REQUIRED, request -> request.startsWith("SASL SCRAM") ? challenge(request)
                : "authenticated as root\nsasl " + base64("v=AAAA"), "did not prove that it knows the password");
    }

    @Test
    void aWeakIterationCountIsRefused() throws Exception {
        refused(REQUIRED, request -> "sasl " + base64(new String(Base64.getDecoder().decode(challenge(request).substring(5)),
                StandardCharsets.UTF_8).replace("i=4096", "i=1")), "iteration count 1 ");
    }

    @Test
    void theShellNeverSendsAPasswordOutsideScram() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        Fake fake = fake("hstore connection 1 at generation 2", request -> {
            received.add(request);
            return "authenticated as admin";
        });
        try (RemoteEndpoint shell = new RemoteEndpoint(fake.address(), Optional.empty())) {
            for (String script : List.of("AUTHENTICATE admin PASSWORD 'pw-secret'; SHOW TYPES;",
                    "AT GENERATION 1 AUTHENTICATE admin PASSWORD 'pw-secret';",
                    "authenticate 'admin' 'pw-secret' ;;x",
                    "SHOW TYPES; Authenticate admin PASSWORD 'pw-secret")) {
                assertTrue(shell.execute(script).startsWith("error: AUTHENTICATE must be sent on its own"), script);
            }
            assertTrue(shell.execute("INSERT NODE Note {text: 'authenticate later'};").startsWith("authenticated"));
        }
        assertTrue(received.stream().noneMatch(request -> request.contains("pw-secret")), received.toString());
    }

    @Test
    void theShellStopsAfterAFailedProof() throws Exception {
        Fake fake = fake(REQUIRED, request -> request.startsWith("SASL SCRAM") ? challenge(request)
                : request.startsWith("SASL ") ? forgedProof() : "[]");
        try (RemoteEndpoint shell = new RemoteEndpoint(fake.address(), Optional.empty())) {
            assertTrue(shell.execute("AUTHENTICATE admin PASSWORD 'pw-secret';").contains("could not prove"));
            assertEquals("error: disconnected; connect again to continue", shell.execute("SHOW TYPES;"));
        }
    }
}
