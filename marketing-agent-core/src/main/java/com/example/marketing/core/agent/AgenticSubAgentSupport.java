package com.example.marketing.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.example.marketing.core.model.ConversationMessage;
import com.example.marketing.core.model.MessageRole;
import com.example.marketing.core.model.SubAgentInvocation;
import com.example.marketing.core.skill.SkillRegistry;
import com.example.marketing.core.skill.SpringAiAlibabaSkillRegistryAdapter;

final class AgenticSubAgentSupport {
    private AgenticSubAgentSupport() {
    }

    static AssistantMessage runReactAgent(String name, String instruction, ChatModel chatModel,
                                          SkillRegistry skillRegistry, List<ToolCallback> tools,
                                          List<Message> messages, String threadId) {
        SpringAiAlibabaSkillRegistryAdapter springSkillRegistry =
                new SpringAiAlibabaSkillRegistryAdapter(skillRegistry);
        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(springSkillRegistry)
                .groupedTools(Map.of())
                .build();
        ReactAgent agent = ReactAgent.builder()
                .name(name)
                .model(chatModel)
                .instruction(instruction)
                .tools(tools)
                .hooks(
                        skillsHook,
                        ModelCallLimitHook.builder().runLimit(8).threadLimit(12).build())
                .interceptors(
                        ToolErrorInterceptor.builder().build(),
                        ToolRetryInterceptor.builder().maxRetries(1).build())
                .enableLogging(true)
                .build();
        try {
            return agent.call(messages, RunnableConfig.builder().threadId(threadId).build());
        }
        catch (Exception ex) {
            throw new IllegalStateException("ReactAgent execution failed", ex);
        }
    }

    static List<Message> toMessages(String systemMessage, SubAgentInvocation invocation, String userTask,
                                    List<ConversationMessage> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemMessage));
        messages.add(new UserMessage("""
                Invocation:
                - invocationId: %s
                - conversationId: %s
                - skillName: %s
                - candidateSkills: %s
                - compressedContext: %s
                - visibleObjects: %s
                - inputs: %s

                User task:
                %s
                """.formatted(
                invocation.invocationId(),
                invocation.conversationId(),
                invocation.skillName(),
                invocation.candidateSkills(),
                invocation.compressedContext(),
                invocation.visibleObjects(),
                invocation.inputs(),
                userTask)));
        if (history != null) {
            history.stream().limit(8).map(AgenticSubAgentSupport::toMessage).forEach(messages::add);
        }
        return messages;
    }

    static Message toMessage(ConversationMessage message) {
        String text = "%s [source=%s, eventType=%s]:%n%s".formatted(
                message.role(), message.source(), message.eventType(), message.content());
        if (message.role() == MessageRole.SYSTEM) {
            return new SystemMessage(text);
        }
        if (message.role() == MessageRole.ASSISTANT) {
            return new AssistantMessage(text);
        }
        return new UserMessage(text);
    }
}
