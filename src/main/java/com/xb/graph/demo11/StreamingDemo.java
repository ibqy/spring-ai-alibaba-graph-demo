package com.xb.graph.demo11;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class StreamingDemo implements GraphDemo {
    @Override
    public String id() { return "demo11"; }

    @Override
    public String title() { return "节点事件流与阶段产物"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("topic", "Graph 检查点"); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(() -> Map.of("trace", new AppendStrategy()))
                .addNode("outline", node_async(state -> {
                    Object topic = state.value("topic").orElse("");
                    if (!(topic instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("topic 必须是非空字符串");
                    }
                    String outline = text.strip() + "：概念 -> 状态 -> 实验";
                    return Map.of("outline", outline, "artifact", outline, "stage", "OUTLINE",
                            "trace", List.of("outline"));
                }))
                .addNode("draft", node_async(state -> {
                    String draft = "教学草稿：" + state.value("outline").orElseThrow()
                            + "。先观察节点状态，再对比执行结果。";
                    return Map.of("draft", draft, "artifact", draft, "stage", "DRAFT",
                            "trace", List.of("draft"));
                }))
                .addNode("publish", node_async(state -> {
                    String article = "已整理的阶段成果：" + state.value("draft").orElseThrow();
                    return Map.of("article", article, "artifact", article, "stage", "PUBLISHED",
                            "streamKind", "NODE_EVENTS", "trace", List.of("publish"));
                }))
                .addEdge(StateGraph.START, "outline")
                .addEdge("outline", "draft")
                .addEdge("draft", "publish")
                .addEdge("publish", StateGraph.END);
    }
}
