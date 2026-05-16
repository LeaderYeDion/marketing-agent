package com.example.marketing.core.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HashingEmbeddingService implements EmbeddingService {
    private final int dimensions;

    public HashingEmbeddingService(@Value("${agent.pgvector.embedding-dimensions:384}") int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        String source = text == null ? "" : text;
        for (String token : source.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            byte[] digest = sha256(token);
            int bucket = Math.floorMod(java.nio.ByteBuffer.wrap(digest, 0, 4).getInt(), dimensions);
            vector[bucket] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    private byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
