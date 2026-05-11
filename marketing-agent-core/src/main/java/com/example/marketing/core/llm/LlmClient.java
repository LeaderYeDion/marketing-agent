package com.example.marketing.core.llm;

import java.util.List;

import com.example.marketing.core.model.ConversationMessage;

public interface LlmClient {
    String generate(String systemMessage, List<ConversationMessage> messages);
}
