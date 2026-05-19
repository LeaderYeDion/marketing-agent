package com.example.marketing.core.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.example.marketing.api.MarketingRequest;
import com.example.marketing.core.memory.HarnessContext;
import com.example.marketing.core.task.TaskGraph;
import com.example.marketing.core.task.TaskNode;

@Service
public class TaskPlanner {
    private static final Pattern ACTIVITY_ID = Pattern.compile("(?i)\\bA\\d{2,}\\b");

    public TaskGraph plan(MarketingRequest request, HarnessContext context) {
        String graphId = "tg_" + UUID.randomUUID().toString().substring(0, 8);
        String query = request.query() == null ? "" : request.query();
        LinkedHashSet<String> capabilityNames = detectCapabilities(query, request.variables());
        List<TaskNode> nodes = new ArrayList<>();
        List<String> dependsOn = List.of();
        int index = 1;
        for (String capabilityName : capabilityNames) {
            String nodeId = "node_" + index++;
            Map<String, Object> inputs = inputsFor(capabilityName, request, query);
            nodes.add(TaskNode.pending(nodeId, goalFor(capabilityName, query), capabilityName, inputs, dependsOn));
            dependsOn = List.of(nodeId);
        }
        return new TaskGraph(graphId, query, nodes, nodes.isEmpty() ? "empty" : "planned");
    }

    private LinkedHashSet<String> detectCapabilities(String query, Map<String, Object> variables) {
        String normalized = query == null ? "" : query.toLowerCase();
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        boolean hasExcel = hasVariable(variables, "excel_file_path")
                || containsAny(normalized, "excel", ".xlsx", ".csv", "spreadsheet", "表格", "文件");
        boolean asksEnrollment = hasVariable(variables, "activity_id")
                || containsAny(normalized, "enroll these", "enroll coupons", "create enrollment",
                "execute enrollment", "报名excel", "执行报名", "优惠券", "coupon");
        boolean asksRule = containsAny(normalized, "rule", "rules", "policy", "promotion", "eligible", "can ",
                "cannot", "规则", "优惠", "活动", "判断", "能不能");
        boolean asksCopy = containsAny(normalized, "copy", "notification", "message", "sms", "文案", "通知", "社群",
                "短信", "话术");

        if (hasExcel || asksEnrollment) {
            capabilities.add("activity_enroll");
        }
        if (asksRule || (!hasExcel && !asksCopy)) {
            capabilities.add("rule_inquiry");
        }
        if (asksCopy) {
            capabilities.add("copywriting");
        }
        if (capabilities.isEmpty()) {
            capabilities.add("rule_inquiry");
        }
        return capabilities;
    }

    private Map<String, Object> inputsFor(String capabilityName, MarketingRequest request, String query) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (request.variables() != null) {
            inputs.putAll(request.variables());
        }
        putIfNotBlank(inputs, "question", query);
        putIfNotBlank(inputs, "channel", request.channel());
        putIfNotBlank(inputs, "product", request.product());
        putIfNotBlank(inputs, "audience", request.audience());
        if (request.goals() != null && !request.goals().isEmpty()) {
            inputs.put("goals", request.goals());
        }
        if (!inputs.containsKey("activity_id")) {
            putIfNotBlank(inputs, "activity_id", extractActivityId(query));
        }
        inputs.put("capability_name", capabilityName);
        return inputs;
    }

    private String goalFor(String capabilityName, String query) {
        return switch (capabilityName) {
            case "activity_enroll" -> "Inspect spreadsheet or prepare activity enrollment context for: " + query;
            case "copywriting" -> "Create marketing copy from prior observations for: " + query;
            default -> "Answer or judge marketing rules for: " + query;
        };
    }

    private boolean hasVariable(Map<String, Object> variables, String key) {
        return variables != null && variables.get(key) != null && !variables.get(key).toString().isBlank();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String extractActivityId(String query) {
        Matcher matcher = ACTIVITY_ID.matcher(query == null ? "" : query);
        return matcher.find() ? matcher.group() : "";
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
