package com.example.marketing.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example.marketing")
public class MarketingAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketingAgentApplication.class, args);
    }
}
