package com.xb.graph.demo02;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class SequenceGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo02"; }

    @Override
    public String title() { return "顺序图：清洗后处理"; }

    @Override
    public String level() { return "basic"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("text", "  hello   Graph  "); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(id(), () -> Map.of("text", new ReplaceStrategy(),
                "cleaned", new ReplaceStrategy(), "result", new ReplaceStrategy()))
                .addNode("clean", node_async(state -> {
                    Object input = state.value("text").orElse("");
                    if (!(input instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("text 必须是非空字符串");
                    }
                    return Map.of("cleaned", text.strip().replaceAll("\\s+", " "));
                }))
                .addNode("process", node_async(state -> Map.of("result",
                        state.<String>value("cleaned").orElseThrow().toUpperCase(Locale.ROOT))))
                .addEdge(StateGraph.START, "clean")
                .addEdge("clean", "process")
                .addEdge("process", StateGraph.END);
    }
}
