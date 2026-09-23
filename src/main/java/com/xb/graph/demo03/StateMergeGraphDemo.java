package com.xb.graph.demo03;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class StateMergeGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo03"; }

    @Override
    public String title() { return "状态合并：Replace 与 Append"; }

    @Override
    public String level() { return "basic"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("text", "Graph"); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(id(), () -> Map.of("text", new ReplaceStrategy(),
                "status", new ReplaceStrategy(), "events", new AppendStrategy()))
                .addNode("draft", node_async(state -> {
                    Object input = state.value("text").orElse("");
                    if (!(input instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("text 必须是非空字符串");
                    }
                    return Map.of("status", "draft", "events", List.of("draft:" + text));
                }))
                .addNode("publish", node_async(state -> Map.of("status", "published",
                        "events", List.of("published:" + state.<String>value("text").orElseThrow()))))
                .addEdge(StateGraph.START, "draft")
                .addEdge("draft", "publish")
                .addEdge("publish", StateGraph.END);
    }
}
