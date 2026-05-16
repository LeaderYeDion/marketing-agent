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
import org.springframework.beans.factory.annotation.Value;

import com.example.marketing.core.model.ConversationMessage;

@Component
public class GeminiLlmClient implements LlmClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String apiKey;
    private final String model;

    public GeminiLlmClient(@Value("${agent.llm.gemini.api-key:}") String apiKey,
                           @Value("${agent.llm.gemini.model:}") String model) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? GeminiConstants.GEMINI_API_KEY : apiKey;
        this.model = model == null || model.isBlank() ? GeminiConstants.GEMINI_MODEL : model;
    }

    @Override
    public String generate(String systemMessage, List<ConversationMessage> messages) {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;
        String body = buildRequest(systemMessage, messages);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LlmRuntimeException(classifyHttpStatus(response.statusCode()),
                        "Gemini request failed with HTTP " + response.statusCode() + ": " + response.body(),
                        response.statusCode() == 429 || response.statusCode() >= 500);
            }
            return JsonSupport.firstTextFromGeminiResponse(response.body());
        }
        catch (IOException ex) {
            throw new LlmRuntimeException(LlmErrorType.UNKNOWN, "Gemini request failed", true, ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmRuntimeException(LlmErrorType.UNKNOWN, "Gemini request interrupted", true, ex);
        }
    }

    private LlmErrorType classifyHttpStatus(int statusCode) {
        if (statusCode == 429) {
            return LlmErrorType.RATE_LIMITED;
        }
        if (statusCode == 403) {
            return LlmErrorType.QUOTA_EXCEEDED;
        }
        if (statusCode >= 500) {
            return LlmErrorType.PROVIDER_5XX;
        }
        return LlmErrorType.PROVIDER_4XX;
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
