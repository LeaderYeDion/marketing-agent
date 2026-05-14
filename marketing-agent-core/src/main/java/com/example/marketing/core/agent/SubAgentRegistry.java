package com.example.marketing.core.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class SubAgentRegistry {
    private final Map<String, SubAgent> agents;

    public SubAgentRegistry(List<SubAgent> agents) {
        this.agents = agents.stream().collect(Collectors.toUnmodifiableMap(SubAgent::name, Function.identity()));
    }

    public Optional<SubAgent> find(String name) {
        return Optional.ofNullable(agents.get(name));
    }
}
