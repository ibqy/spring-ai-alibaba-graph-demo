package com.xb.graph.demo01;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class HelloGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo01"; }

    @Override
    public String title() { return "Hello Graph：第一个节点"; }

    @Override
    public String level() { return "basic"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("name", "小北"); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(id(), () -> Map.of("name", new ReplaceStrategy(),
                "greeting", new ReplaceStrategy()))
                .addNode("greet", node_async(state -> {
                    Object input = state.value("name").orElse("");
                    if (!(input instanceof String name) || name.isBlank()) {
                        throw new IllegalArgumentException("name 不能为空，且必须是字符串");
                    }
                    return Map.of("greeting", "你好，" + name.strip() + "！欢迎学习 Graph。");
                }))
                .addEdge(StateGraph.START, "greet")
                .addEdge("greet", StateGraph.END);
    }
}
