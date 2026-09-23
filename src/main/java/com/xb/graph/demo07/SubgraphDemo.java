package com.xb.graph.demo07;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class SubgraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo07"; }

    @Override
    public String title() { return "子图：组合可复用流程"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("name", " 小北 "); }

    @Override
    public StateGraph graph() throws GraphStateException {
        StateGraph child = new StateGraph("greeting-child", this::strategies)
                .addNode("normalize", node_async(state -> {
                    Object input = state.value("name").orElse("");
                    if (!(input instanceof String name) || name.isBlank()) {
                        throw new IllegalArgumentException("name 不能为空，且必须是字符串");
                    }
                    return Map.of("cleanedName", name.strip());
                }))
                .addNode("greet", node_async(state -> Map.of("greeting",
                        "子图你好，" + state.<String>value("cleanedName").orElseThrow() + "！")))
                .addEdge(StateGraph.START, "normalize")
                .addEdge("normalize", "greet")
                .addEdge("greet", StateGraph.END);

        return new StateGraph(id(), this::strategies)
                .addNode("prepare", node_async(state -> Map.of("source", "parent")))
                .addNode("greetingChild", child)
                .addNode("finish", node_async(state -> Map.of("result",
                        "[" + state.<String>value("greeting").orElseThrow() + "]")))
                .addEdge(StateGraph.START, "prepare")
                .addEdge("prepare", "greetingChild")
                .addEdge("greetingChild", "finish")
                .addEdge("finish", StateGraph.END);
    }

    private Map<String, KeyStrategy> strategies() {
        return Map.of("name", new ReplaceStrategy(), "source", new ReplaceStrategy(),
                "cleanedName", new ReplaceStrategy(), "greeting", new ReplaceStrategy(),
                "result", new ReplaceStrategy());
    }
}
