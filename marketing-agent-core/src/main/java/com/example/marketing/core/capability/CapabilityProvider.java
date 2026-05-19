package com.example.marketing.core.capability;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.observation.Observation;

public interface CapabilityProvider {
    String providerName();

    boolean supports(CapabilityDescriptor descriptor);

    Observation execute(CapabilityExecutionRequest executionRequest, MarketingRequest marketingRequest);
}
