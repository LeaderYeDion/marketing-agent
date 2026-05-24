package com.example.marketing.core.worker;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.observation.Observation;

public interface WorkerProvider {
    String providerName();

    boolean supports(WorkerDescriptor descriptor);

    Observation execute(WorkerExecutionRequest executionRequest, MarketingRequest marketingRequest);
}

