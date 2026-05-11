package com.example.marketing.core.rag;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.marketing.core.context.MarketingAgentContext;

@Service
public class InMemoryMarketingRagService implements RagService {
    private final List<RagDocument> documents = List.of(
            new RagDocument("policy-001", "会员拉新活动规则",
                    "拉新活动需要明确目标人群、奖励门槛、预算上限、风控规则和投放渠道。",
                    Map.of("type", "policy")),
            new RagDocument("case-001", "新品冷启动营销案例",
                    "新品冷启动建议先用种子用户验证卖点，再通过短视频、社群和达人内容扩大声量。",
                    Map.of("type", "case")),
            new RagDocument("metric-001", "营销效果指标",
                    "营销计划应追踪曝光、点击率、转化率、获客成本、复购率和活动 ROI。",
                    Map.of("type", "metric")),
            new RagDocument("risk-001", "优惠券活动风控",
                    "优惠券活动需要限制领取频次、核销门槛、设备指纹和异常账户拦截策略。",
                    Map.of("type", "risk"))
    );

    @Override
    public List<RagDocument> retrieve(String query, MarketingAgentContext context) {
        String searchText = ((query == null ? "" : query) + " " + context.product() + " " + context.audience())
                .toLowerCase(Locale.ROOT);
        return documents.stream()
                .sorted(Comparator.comparingInt(document -> -score(document, searchText)))
                .limit(3)
                .toList();
    }

    private int score(RagDocument document, String searchText) {
        int score = 0;
        String haystack = (document.title() + " " + document.content()).toLowerCase(Locale.ROOT);
        for (String token : searchText.split("\\s+")) {
            if (!token.isBlank() && haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }
}
