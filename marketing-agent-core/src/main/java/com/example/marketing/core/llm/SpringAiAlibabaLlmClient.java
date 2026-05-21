package com.example.marketing.core.llm;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.MessageRole;

@Component
public class SpringAiAlibabaLlmClient implements LlmClient {
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final String configuredModel;

    public SpringAiAlibabaLlmClient(ObjectProvider<ChatModel> chatModelProvider,
                                    @Value("${agent.llm.default-model:${spring.ai.google.genai.chat.options.model:qwen-plus}}")
                                    String configuredModel) {
        this.chatModelProvider = chatModelProvider;
        this.configuredModel = configuredModel == null ? "" : configuredModel;
    }

    @Override
    public String generate(String systemMessage, List<ConversationMessage> messages) {
        return generate(LlmRequest.simple("default", systemMessage, messages));
    }

    @Override
    public String generate(LlmRequest request) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new LlmRuntimeException(LlmErrorType.PROVIDER_4XX,
                    "Spring AI ChatModel is not available. Configure Spring AI Alibaba DashScope before calling LLM.",
                    false);
        }
        try {
            ChatResponse response = chatModel.call(new Prompt(toMessages(request), chatOptions(request)));
            return response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? ""
                    : response.getResult().getOutput().getText();
        }
        catch (LlmRuntimeException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new LlmRuntimeException(classify(ex), "Spring AI Alibaba chat request failed: " + ex.getMessage(),
                    true, ex);
        }
    }

    private List<Message> toMessages(LlmRequest request) {
        List<Message> converted = new ArrayList<>();
        if (request.systemMessage() != null && !request.systemMessage().isBlank()) {
            converted.add(new SystemMessage(request.systemMessage()));
        }
        for (ConversationMessage message : request.messages()) {
            converted.add(toMessage(message));
        }
        return converted;
    }

    private Message toMessage(ConversationMessage message) {
        if (message.role() == MessageRole.SYSTEM) {
            return new SystemMessage(message.content());
        }
        if (message.role() == MessageRole.ASSISTANT) {
            return new AssistantMessage(formatContent(message));
        }
        return new UserMessage(formatContent(message));
    }

    private String formatContent(ConversationMessage message) {
        if ((message.source() == null || message.source().isBlank())
                && (message.eventType() == null || message.eventType().isBlank())) {
            return nullToBlank(message.content());
        }
        return "%s [source=%s, eventType=%s]:%n%s".formatted(
                message.role(),
                nullToBlank(message.source()),
                nullToBlank(message.eventType()),
                nullToBlank(message.content()));
    }

    private ChatOptions chatOptions(LlmRequest request) {
        ChatOptions.Builder builder = ChatOptions.builder()
                .temperature(request.temperature());
        String model = firstNonBlank(request.model(), configuredModel);
        if (!model.isBlank()) {
            builder.model(model);
        }
        if (request.maxTokens() > 0) {
            builder.maxTokens(request.maxTokens());
        }
        return builder.build();
    }

    private LlmErrorType classify(RuntimeException ex) {
        String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) {
            return LlmErrorType.TIMEOUT;
        }
        if (message.contains("429") || message.contains("rate limit") || message.contains("throttl")) {
            return LlmErrorType.RATE_LIMITED;
        }
        if (message.contains("quota")) {
            return LlmErrorType.QUOTA_EXCEEDED;
        }
        if (message.contains("5xx") || message.contains("http 5")) {
            return LlmErrorType.PROVIDER_5XX;
        }
        if (message.contains("4xx") || message.contains("http 4")) {
            return LlmErrorType.PROVIDER_4XX;
        }
        return LlmErrorType.UNKNOWN;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? nullToBlank(second) : first;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
