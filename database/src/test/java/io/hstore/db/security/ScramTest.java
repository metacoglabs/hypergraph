// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScramTest {

    private static final byte[] RFC_SALT = Base64.getDecoder().decode("W22ZaJ0SNY7soEsUEjb6gQ==");
    private static final String RFC_CLIENT_NONCE = "rOprNGfwEbeRWgbNEkqO";
    private static final String RFC_SERVER_NONCE = "%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0";
    private static final byte[] SECRET = new byte[32];

    private static Function<String, Optional<Scram.Verifier>> only(String user, Scram.Verifier verifier) {
        return name -> name.equals(user) ? Optional.of(verifier) : Optional.empty();
    }

    @Test
    void matchesTheRfc7677Example() {
        Scram.Verifier verifier = Scram.Verifier.of("pencil", RFC_SALT, 4096);
        Scram.Client client = new Scram.Client("user", "pencil", Optional.empty(), RFC_CLIENT_NONCE);
        Scram.Server server = new Scram.Server(only("user", verifier), SECRET, 600_000, Optional.empty(), RFC_SERVER_NONCE);
        String first = client.first();
        assertEquals("n,,n=user,r=rOprNGfwEbeRWgbNEkqO", first);
        String serverFirst = server.first(Scram.MECHANISM, first);
        assertEquals("r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096", serverFirst);
        String last = client.last(serverFirst);
        assertEquals("c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=", last);
        Scram.Outcome.Accepted accepted = assertInstanceOf(Scram.Outcome.Accepted.class, server.last(last));
        assertEquals("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=", accepted.serverFinal());
        client.verify(accepted.serverFinal());
        assertThrows(HStoreException.class, () -> client.verify("v=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        assertThrows(HStoreException.class, () -> client.verify("e=invalid-proof"));
    }

    @Test
    void wrongPasswordsAndUnknownUsersAreRejectedAlike() {
        Scram.Verifier verifier = Scram.Verifier.of("correct horse", RFC_SALT, 4096);
        for (String[] attempt : new String[][]{{"alice", "wrong horse"}, {"mallory", "correct horse"}}) {
            Scram.Client client = new Scram.Client(attempt[0], attempt[1], Optional.empty());
            Scram.Server server = new Scram.Server(only("alice", verifier), SECRET, 4096, Optional.empty());
            Scram.Outcome outcome = server.last(client.last(server.first(Scram.MECHANISM, client.first())));
            assertEquals(new Scram.Outcome.Rejected(attempt[0]), outcome);
        }
        Scram.Server unknown = new Scram.Server(name -> Optional.empty(), SECRET, 600_000, Optional.empty());
        String first = unknown.first(Scram.MECHANISM, "n,,n=nobody,r=abc");
        Scram.Server again = new Scram.Server(name -> Optional.empty(), SECRET, 600_000, Optional.empty());
        String repeated = again.first(Scram.MECHANISM, "n,,n=nobody,r=abc");
        assertEquals(first.substring(first.indexOf(",s=")), repeated.substring(repeated.indexOf(",s=")));
        assertTrue(first.endsWith(",i=600000"));
        Scram.Server other = new Scram.Server(name -> Optional.empty(), new byte[]{1}, 600_000, Optional.empty());
        String otherSalt = other.first(Scram.MECHANISM, "n,,n=nobody,r=abc");
        assertNotEquals(first.substring(first.indexOf(",s=")), otherSalt.substring(otherSalt.indexOf(",s=")));
    }

    @Test
    void channelBindingTiesTheProofToTheCertificate() {
        Scram.Verifier verifier = Scram.Verifier.of("pencil-pencil", RFC_SALT, 4096);
        byte[] certificate = new byte[32];
        certificate[0] = 7;
        Scram.Client bound = new Scram.Client("user", "pencil-pencil", Optional.of(certificate));
        Scram.Server server = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.of(certificate));
        assertEquals(Scram.MECHANISM_PLUS, bound.mechanism());
        Scram.Outcome.Accepted accepted = assertInstanceOf(Scram.Outcome.Accepted.class,
                server.last(bound.last(server.first(bound.mechanism(), bound.first()))));
        bound.verify(accepted.serverFinal());

        byte[] relayed = new byte[32];
        Scram.Client fooled = new Scram.Client("user", "pencil-pencil", Optional.of(relayed));
        Scram.Server real = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.of(certificate));
        assertInstanceOf(Scram.Outcome.Rejected.class, real.last(fooled.last(real.first(fooled.mechanism(), fooled.first()))));

        Scram.Client unbound = new Scram.Client("user", "pencil-pencil", Optional.empty());
        Scram.Server tls = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.of(certificate));
        assertInstanceOf(Scram.Outcome.Accepted.class, tls.last(unbound.last(tls.first(Scram.MECHANISM, unbound.first()))));

        Scram.Server downgraded = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.of(certificate));
        assertThrows(HStoreException.class, () -> downgraded.first(Scram.MECHANISM, "y,,n=user,r=abc"));
        Scram.Server plain = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.empty());
        assertThrows(HStoreException.class, () -> plain.first(Scram.MECHANISM_PLUS, "p=tls-server-end-point,,n=user,r=abc"));
        Scram.Server mismatched = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.of(certificate));
        assertThrows(HStoreException.class, () -> mismatched.first(Scram.MECHANISM, "p=tls-server-end-point,,n=user,r=abc"));
        Scram.Server yes = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.empty());
        yes.first(Scram.MECHANISM, "y,,n=user,r=abc");
    }

    @Test
    void malformedMessagesFailCleanly() {
        Scram.Verifier verifier = Scram.Verifier.of("pencil-pencil", RFC_SALT, 4096);
        for (String bad : List.of("", "n,,", "n,,r=abc", "n,,n=user", "x,,n=user,r=abc", "n,a=admin,n=user,r=abc", "n,,n=us=er,r=abc",
                "n,,n=user,r=a,b", "n,,n=user,r=abc,m=ext", "n,," + "x".repeat(5000))) {
            Scram.Server server = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.empty());
            assertThrows(HStoreException.class, () -> server.first(Scram.MECHANISM, bad), bad);
        }
        assertThrows(HStoreException.class, () -> new Scram.Server(only("user", verifier), SECRET, 4096, Optional.empty())
                .first("SCRAM-SHA-1", "n,,n=user,r=abc"));
        for (String bad : List.of("c=biws,r=abc", "c=biws,r=abc,p=!!!", "")) {
            Scram.Server server = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.empty());
            server.first(Scram.MECHANISM, "n,,n=user,r=abc");
            assertThrows(HStoreException.class, () -> server.last(bad), bad);
        }
        Scram.Server replayed = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.empty());
        Scram.Client client = new Scram.Client("user", "pencil-pencil", Optional.empty());
        String last = client.last(replayed.first(Scram.MECHANISM, client.first()));
        assertInstanceOf(Scram.Outcome.Accepted.class, replayed.last(last));
        assertThrows(HStoreException.class, () -> replayed.last(last));
        Scram.Server wrongNonce = new Scram.Server(only("user", verifier), SECRET, 4096, Optional.empty());
        wrongNonce.first(Scram.MECHANISM, "n,,n=user,r=abc");
        assertInstanceOf(Scram.Outcome.Rejected.class, wrongNonce.last(last));
        Scram.Client cautious = new Scram.Client("user", "pencil-pencil", Optional.empty(), "abc");
        cautious.first();
        assertThrows(HStoreException.class, () -> cautious.last("r=zzz,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"));
        assertThrows(HStoreException.class, () -> cautious.last("r=abcdef,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1"));
        assertThrows(HStoreException.class, () -> cautious.last("r=abcdef,s=@@,i=4096"));
    }

    @Test
    void verifiersRoundTripAndLegacyHashesConvert() throws Exception {
        Scram.Verifier verifier = Scram.Verifier.of("pencil-pencil", RFC_SALT, 4096);
        assertEquals(Optional.of(verifier), Scram.Verifier.parse(verifier.encode()));
        assertTrue(verifier.encode().startsWith("SCRAM-SHA-256$4096:W22ZaJ0SNY7soEsUEjb6gQ==$"));
        for (String bad : List.of("", "SCRAM-SHA-256$", "SCRAM-SHA-256$x:AA==$AA==:AA==", "SCRAM-SHA-256$4096:AA==$AA==:AA==",
                "SCRAM-SHA-256$0:" + verifier.encode().substring(verifier.encode().indexOf(':') + 1), "md5abc")) {
            assertEquals(Optional.empty(), Scram.Verifier.parse(bad), bad);
        }
        assertTrue(verifier.matches("pencil-pencil"));
        assertTrue(!verifier.matches("pencil-pencil "));
        String password = "pässwörd-🔒";
        byte[] salted = Scram.saltedPassword(password, RFC_SALT, 4096);
        assertArrayEquals(pbkdf2(password.getBytes(StandardCharsets.UTF_8), RFC_SALT, 4096), salted);
        Security.User legacy = new Security.User("legacy", HexFormat.of().formatHex(RFC_SALT), HexFormat.of().formatHex(salted), 4096, 0, Role.READER);
        assertTrue(Security.legacy(legacy));
        assertEquals(Scram.Verifier.of(password, RFC_SALT, 4096), Security.verifier(legacy));
        assertTrue(Security.verifier(legacy).matches(password));
    }

    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        mac.update(salt);
        byte[] block = mac.doFinal(new byte[]{0, 0, 0, 1});
        byte[] result = block.clone();
        for (int i = 1; i < iterations; i++) {
            block = mac.doFinal(block);
            for (int j = 0; j < result.length; j++) {
                result[j] ^= block[j];
            }
        }
        return result;
    }
}
