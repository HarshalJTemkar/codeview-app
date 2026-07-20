package com.codeview.app.hash;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * SHA-256 content hashing for chunks, with Merkle-style aggregation up the
 * directory tree (child hashes -> parent hash), per Architecture Plan §5.
 * A root-hash comparison lets the indexer skip unchanged branches entirely.
 */
@Component
public class MerkleHasher {

    public String hashContent(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Aggregates a list of child hashes (chunk hashes within a file, or file
     * hashes within a directory) into a single parent hash, in the order given.
     * Order matters — callers should sort children (e.g. by chunk_id or file
     * name) before calling this so the same set of children always produces
     * the same parent hash.
     */
    public String aggregate(List<String> childHashes) {
        StringBuilder combined = new StringBuilder();
        for (String h : childHashes) {
            combined.append(h);
        }
        return sha256Hex(combined.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every standard JVM; this is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
