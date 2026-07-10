package com.example.mosip.service;

import com.example.mosip.entity.hashing.UinHashSalt;
import com.example.mosip.repository.hashing.UinHashSaltRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * MOSIP-style salt-modulo hashing.
 * <p>
 * A salt is selected per identifier by its modulo bucket ({@code id mod modulo}),
 * and the stored value is {@code SHA-256(id + salt)} in hex. Because the bucket is
 * derived from the identifier itself, the hash stays deterministic and queryable
 * while defeating precomputed-rainbow-table attacks (each bucket has its own salt).
 * <p>
 * The salt table ({@code uin_hash_salt}) is seeded once with {@code modulo} random
 * salts on startup; existing salts are never overwritten.
 */
@Service
public class SaltModuloHashService {

    private final UinHashSaltRepository saltRepository;
    private final int modulo;
    private final SecureRandom secureRandom = new SecureRandom();

    public SaltModuloHashService(UinHashSaltRepository saltRepository,
                                 @Value("${mosip.ida.salt.modulo:1000}") int modulo) {
        this.saltRepository = saltRepository;
        this.modulo = modulo;
    }

    /**
     * Seeds the salt table with one random salt per modulo bucket if it is empty.
     */
    @PostConstruct
    @Transactional("hashingTransactionManager")
    public void seedSaltTable() {
        try {
            long existing = saltRepository.count();
            if (existing >= modulo) {
                System.out.println("Salt table already seeded (" + existing + " buckets).");
                return;
            }
            List<UinHashSalt> salts = new ArrayList<>();
            for (long bucket = 0; bucket < modulo; bucket++) {
                if (!saltRepository.existsById(bucket)) {
                    salts.add(new UinHashSalt(bucket, generateSalt()));
                }
            }
            saltRepository.saveAll(salts);
            System.out.println("Seeded " + salts.size() + " salt buckets (modulo=" + modulo + ").");
        } catch (Exception e) {
            System.err.println("Failed to seed salt table: " + e.getMessage());
        }
    }

    /**
     * Computes the salt-modulo hash of the given identifier:
     * {@code SHA-256(id + salt[id mod modulo])} as a lowercase hex string.
     *
     * @param id the identifier to hash (e.g. a UIN or an individual ID)
     * @return the salted hash, or {@code null} if {@code id} is null
     */
    public String hash(String id) {
        if (id == null) {
            return null;
        }
        long bucket = getBucket(id);
        String salt = saltRepository.findById(bucket)
                .map(UinHashSalt::getSalt)
                .orElseThrow(() -> new IllegalStateException(
                        "No salt found for bucket " + bucket + "; salt table not seeded."));
        return sha256Hex(id + salt);
    }

    /**
     * Derives the salt bucket for an identifier. Numeric identifiers (e.g. UIN) use
     * {@code value mod modulo}, matching MOSIP; non-numeric identifiers fall back to a
     * stable positive modulo of their hash code.
     */
    private long getBucket(String id) {
        try {
            return Math.floorMod(Long.parseLong(id.trim()), (long) modulo);
        } catch (NumberFormatException e) {
            return Math.floorMod((long) id.hashCode(), (long) modulo);
        }
    }

    private String generateSalt() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 cryptographic algorithm not found", e);
        }
    }
}
