package com.xb.graph.demo10;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class HumanReviewDemo implements GraphDemo {
    @Override
    public String id() { return "demo10"; }

    @Override
    public String title() { return "人工审批与断点恢复"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("request", "发布 Graph 教学文章"); }

    @Override
    public Set<String> reviewNodes() { return Set.of("review"); }

    @Override
    public StateGraph graph() throws GraphStateException {
        // 1.1.2.2 恢复时只装载已注册策略的字段，普通执行的默认覆盖不足以支持恢复。
        return new StateGraph(() -> Map.of("trace", new AppendStrategy(),
                        "request", new ReplaceStrategy(), "draft", new ReplaceStrategy(),
                        "decision", new ReplaceStrategy(), "approved", new ReplaceStrategy(),
                        "reviewed", new ReplaceStrategy(), "result", new ReplaceStrategy()))
                .addNode("prepare", node_async(state -> {
                    Object request = state.value("request").orElse("");
                    if (!(request instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("request 必须是非空字符串");
                    }
                    return Map.of("draft", "待审批：" + text.strip(), "decision", "PENDING",
                            "trace", List.of("prepare"));
                }))
                // 运行器在该节点之前暂停；审批人更新 approved 后才执行节点。
                .addNode("review", node_async(state -> {
                    Object approved = state.value("approved").orElse(null);
                    if (!(approved instanceof Boolean)) {
                        throw new IllegalArgumentException("approved 必须是 Boolean，且应通过 resume 提交");
                    }
                    return Map.of("reviewed", true, "trace", List.of("review"));
                }))
                .addNode("approve", node_async(state -> Map.of("decision", "APPROVED",
                        "result", "审批通过，可发布：" + state.value("request").orElseThrow(),
                        "trace", List.of("approve"))))
                .addNode("reject", node_async(state -> Map.of("decision", "REJECTED",
                        "result", "审批拒绝，不发布", "trace", List.of("reject"))))
                .addEdge(StateGraph.START, "prepare")
                .addEdge("prepare", "review")
                .addConditionalEdges("review", edge_async(state ->
                                Boolean.TRUE.equals(state.value("approved").orElse(false)) ? "yes" : "no"),
                        Map.of("yes", "approve", "no", "reject"))
                .addEdge("approve", StateGraph.END)
                .addEdge("reject", StateGraph.END);
    }
}
