package com.example.marketing.core.context;

import com.example.marketing.api.MarketingStreamEvent;
import com.example.marketing.api.MarketingStreamListener;

public final class MarketingEventPublisher {
    private static final ThreadLocal<MarketingStreamListener> LISTENER = new ThreadLocal<>();

    private MarketingEventPublisher() {
    }

    public static void withListener(MarketingStreamListener listener, Runnable runnable) {
        LISTENER.set(listener);
        try {
            runnable.run();
        }
        finally {
            LISTENER.remove();
        }
    }

    public static void publish(MarketingStreamEvent event) {
        MarketingStreamListener listener = LISTENER.get();
        if (listener != null) {
            listener.onEvent(event);
        }
    }
}
