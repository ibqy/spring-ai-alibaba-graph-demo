package com.xb.graph.demo06;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class ParallelGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo06"; }

    @Override
    public String title() { return "并行图：分发与汇聚"; }

    @Override
    public String level() { return "basic"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("text", "Spring AI Graph"); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(id(), () -> Map.of("text", new ReplaceStrategy(),
                "normalized", new ReplaceStrategy(), "characters", new ReplaceStrategy(),
                "words", new ReplaceStrategy(), "uppercase", new ReplaceStrategy(),
                "parts", new AppendStrategy(), "result", new ReplaceStrategy()))
                .addNode("prepare", node_async(state -> {
                    Object input = state.value("text").orElse("");
                    if (!(input instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("text 必须是非空字符串");
                    }
                    return Map.of("normalized", text.strip().replaceAll("\\s+", " "));
                }))
                .addNode("characters", node_async(state -> {
                    int count = state.<String>value("normalized").orElseThrow().length();
                    return Map.of("characters", count, "parts", List.of("characters=" + count));
                }))
                .addNode("words", node_async(state -> {
                    int count = state.<String>value("normalized").orElseThrow().split(" ").length;
                    return Map.of("words", count, "parts", List.of("words=" + count));
                }))
                .addNode("uppercase", node_async(state -> {
                    String text = state.<String>value("normalized").orElseThrow().toUpperCase(Locale.ROOT);
                    return Map.of("uppercase", text, "parts", List.of("uppercase=" + text));
                }))
                .addNode("aggregate", node_async(state -> Map.of("result",
                        state.<List<String>>value("parts").orElseThrow().stream()
                                .sorted().collect(Collectors.joining(" | ")))))
                .addEdge(StateGraph.START, "prepare")
                .addEdge("prepare", List.of("characters", "words", "uppercase"))
                .addEdge(List.of("characters", "words", "uppercase"), "aggregate")
                .addEdge("aggregate", StateGraph.END);
    }
}
