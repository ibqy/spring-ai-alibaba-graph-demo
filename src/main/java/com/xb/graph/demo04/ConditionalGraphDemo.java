package com.xb.graph.demo04;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class ConditionalGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo04"; }

    @Override
    public String title() { return "条件边：按分数分流"; }

    @Override
    public String level() { return "basic"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("score", 80); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(id(), () -> Map.of("score", new ReplaceStrategy(),
                "passed", new ReplaceStrategy(), "result", new ReplaceStrategy()))
                .addNode("evaluate", node_async(state -> {
                    Object input = state.value("score").orElse(null);
                    if (!(input instanceof Number score) || !Double.isFinite(score.doubleValue())
                            || score.doubleValue() < 0 || score.doubleValue() > 100) {
                        throw new IllegalArgumentException("score 必须是 0 到 100 的数字");
                    }
                    return Map.of("passed", score.doubleValue() >= 60);
                }))
                .addNode("pass", node_async(state -> Map.of("result", "通过")))
                .addNode("revise", node_async(state -> Map.of("result", "请修改")))
                .addEdge(StateGraph.START, "evaluate")
                .addConditionalEdges("evaluate", edge_async(state ->
                        state.<Boolean>value("passed").orElseThrow() ? "yes" : "no"),
                        Map.of("yes", "pass", "no", "revise"))
                .addEdge("pass", StateGraph.END)
                .addEdge("revise", StateGraph.END);
    }
}
