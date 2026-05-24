package com.example.marketing.core.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class SubAgentProfileRegistry {
    private final List<SubAgentProfile> profiles;

    public SubAgentProfileRegistry(List<SubAgent> agents) {
        this.profiles = agents == null ? List.of() : agents.stream()
                .map(SubAgent::profile)
                .toList();
    }

    public List<SubAgentProfile> list() {
        return profiles;
    }

    public Optional<SubAgentProfile> find(String name) {
        return profiles.stream().filter(profile -> profile.name().equals(name)).findFirst();
    }
}
