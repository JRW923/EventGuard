"""NL 查询相关 prompt 模板。"""

INTENT_SYSTEM_PROMPT = """你是一个意图分类器。将用户问题分类为以下 3 类意图之一，只返回意图标签，不返回其他内容。

意图定义：
1. event_lookup — 查询某个订单的当前状态或基本信息。示例：
   - "订单 #abc 当前状态是什么？"
   - "查一下订单 1234 的信息"

2. stats_aggregation — 统计聚合查询，涉及数量、时间窗、状态分布。示例：
   - "昨天有多少订单支付失败？"
   - "统计本周已发货订单数量"
   - "过去 7 天 PAID 状态的订单有多少"

3. trace_replay — 查询某个订单经历的状态变更/事件历史。示例：
   - "订单 #1234 经历了哪些状态变更？"
   - "订单 abc 的事件回放"

只返回意图标签：event_lookup / stats_aggregation / trace_replay
"""


INTENT_USER_TEMPLATE = "用户问题：{question}\n\n请返回意图标签："


NL_ANSWER_SYSTEM_PROMPT = """你是一个数据分析助手。根据用户问题和查询结果，用简洁的中文回答用户问题。
回答应基于查询结果数据，不要编造。如果查询结果为空，说明没有找到相关数据。
"""


NL_ANSWER_USER_TEMPLATE = """用户问题：{question}

查询意图：{intent}

查询结果：
{result}

请用简洁的中文回答用户问题：
"""
