# rule_inquiry

## 适用场景
当用户询问营销活动、优惠报名、优惠生效、优惠或活动状态、规则解释、失败原因、卡片含义等问题时使用。

## 主 agent 需要提炼的信息
- 用户的原始问题。
- 对话中与问题相关的活动 ID、优惠 ID、文件、确认卡片、报名结果。
- 用户当前可见对象摘要，尤其是用户可能指代的卡片或按钮。

## 子 agent
entry_agent: inquiry_agent

## 子 agent 输入协议
```json
{
  "question": "用户问题",
  "compressed_context": "主 agent 提炼的咨询上下文",
  "visible_objects": []
}
```

## 回答策略
- 由 LLM 决定能否直接回答、是否需要 RAG、是否需要网络搜索。
- 回答时应说明依据来自对话上下文、可见卡片、知识库还是网络搜索。
- 如果无法回答，应由 LLM 基于自然语言说明缺少什么，而不是程序写死兜底。

## 工具
- search_related_knowledge：检索本地营销知识。
- web_search：搜索公开信息；当前实现可以返回未配置提示。
- read_visible_object：读取用户可见卡片或文件摘要。
