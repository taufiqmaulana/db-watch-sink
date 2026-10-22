package io.datasync.server;

import io.quarkus.logging.Log;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * AES-256-GCM for credentials at rest. The key comes from {@code DATASYNC_MASTER_KEY} (32 bytes, base64);
 * without it, a key file is generated in the data directory.
 */
@Singleton
public class Secrets {

    private static final int IV_BYTES = 12;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public Secrets(@ConfigProperty(name = "datasync.master-key") Optional<String> masterKey,
                   @ConfigProperty(name = "datasync.data-dir") String dataDir) {
        String encoded = masterKey.orElseGet(() -> keyFile(Path.of(dataDir, "master.key")));
        byte[] raw = Base64.getDecoder().decode(encoded.trim());
        if (raw.length != 32) throw new IllegalStateException("DATASYNC_MASTER_KEY must be 32 bytes, base64 encoded");
        key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot decrypt a stored credential; was the master key changed?", e);
        }
    }

    private static String keyFile(Path file) {
        try {
            if (Files.exists(file)) return Files.readString(file);
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String encoded = Base64.getEncoder().encodeToString(raw);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, encoded);
            Log.warnf("DATASYNC_MASTER_KEY is not set, generated %s. Back it up: stored credentials cannot be decrypted without it.", file);
            return encoded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
