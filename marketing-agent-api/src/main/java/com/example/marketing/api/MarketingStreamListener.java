package com.example.marketing.api;

@FunctionalInterface
public interface MarketingStreamListener {
    void onEvent(MarketingStreamEvent event);
}
