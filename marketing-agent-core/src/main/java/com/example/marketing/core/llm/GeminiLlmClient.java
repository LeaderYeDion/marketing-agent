package com.example.marketing.core.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.marketing.core.model.ConversationMessage;

@Component
public class GeminiLlmClient implements LlmClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public String generate(String systemMessage, List<ConversationMessage> messages) {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + GeminiConstants.GEMINI_MODEL + ":generateContent?key=" + GeminiConstants.GEMINI_API_KEY;
        String body = buildRequest(systemMessage, messages);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Gemini request failed with HTTP " + response.statusCode() + ": "
                        + response.body());
            }
            return JsonSupport.firstTextFromGeminiResponse(response.body());
        }
        catch (IOException ex) {
            throw new IllegalStateException("Gemini request failed", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini request interrupted", ex);
        }
    }

    private String buildRequest(String systemMessage, List<ConversationMessage> messages) {
        StringBuilder text = new StringBuilder();
        text.append("SYSTEM:\n").append(systemMessage).append("\n\n");
        for (ConversationMessage message : messages) {
            text.append(message.role()).append(" [source=").append(message.source())
                    .append(", eventType=").append(message.eventType()).append("]:\n")
                    .append(message.content()).append("\n\n");
        }
        return """
                {
                  "contents": [
                    {
                      "role": "user",
                      "parts": [
                        {"text": %s}
                      ]
                    }
                  ],
                  "generationConfig": {
                    "temperature": 0.2
                  }
                }
                """.formatted(JsonSupport.quote(text.toString()));
    }
}
