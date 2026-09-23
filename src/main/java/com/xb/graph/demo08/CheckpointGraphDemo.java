package com.xb.graph.demo08;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class CheckpointGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo08"; }

    @Override
    public String title() { return "检查点：观察分步状态与历史"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("text", " Graph "); }

    @Override
    public StateGraph graph() throws GraphStateException {
        // Saver and threadId belong to the caller, not to this reusable graph definition.
        return new StateGraph(id(), () -> Map.of("text", new ReplaceStrategy(),
                "normalized", new ReplaceStrategy(), "length", new ReplaceStrategy(),
                "summary", new ReplaceStrategy()))
                .addNode("normalize", node_async(state -> {
                    Object input = state.value("text").orElse("");
                    if (!(input instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("text 必须是非空字符串");
                    }
                    return Map.of("normalized", text.strip());
                }))
                .addNode("measure", node_async(state -> Map.of("length",
                        state.<String>value("normalized").orElseThrow().length())))
                .addNode("summarize", node_async(state -> Map.of("summary",
                        state.<String>value("normalized").orElseThrow() + " ("
                                + state.<Integer>value("length").orElseThrow() + ")")))
                .addEdge(StateGraph.START, "normalize")
                .addEdge("normalize", "measure")
                .addEdge("measure", "summarize")
                .addEdge("summarize", StateGraph.END);
    }
}
