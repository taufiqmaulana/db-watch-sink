package io.datasync.server.auth;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Argon2id hashes in the PHC string format, with OWASP's minimum parameters (19 MiB, 2 passes). */
public final class Passwords {

    public static final int MIN_LENGTH = 10;

    private static final int MEMORY_KB = 19_456;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Verified against when a username is unknown, so both cases take the same time. */
    private static final String DUMMY = hash("not-a-real-password");

    private Passwords() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM, 32);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s".formatted(MEMORY_KB, ITERATIONS, PARALLELISM,
            b64.encodeToString(salt), b64.encodeToString(hash));
    }

    /** {@code encoded} may be null (unknown user); the check still runs and returns false. */
    public static boolean verify(String password, String encoded) {
        String[] parts = (encoded == null ? DUMMY : encoded).split("\\$");
        int m = 0, t = 0, p = 0;
        for (String param : parts[3].split(",")) {
            int value = Integer.parseInt(param.substring(2));
            switch (param.charAt(0)) {
                case 'm' -> m = value;
                case 't' -> t = value;
                case 'p' -> p = value;
                default -> throw new IllegalArgumentException("bad Argon2 parameter " + param);
            }
        }
        byte[] expected = Base64.getDecoder().decode(parts[5]);
        boolean match = MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(parts[4]), m, t, p, expected.length));
        return encoded != null && match;
    }

    private static byte[] derive(String password, byte[] salt, int memoryKb, int iterations, int parallelism, int length) {
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build());
        byte[] out = new byte[length];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
