package com.xb.graph.demo05;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class LoopGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo05"; }

    @Override
    public String title() { return "循环图：目标与轮数双重退出"; }

    @Override
    public String level() { return "basic"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("target", 6, "maxRounds", 5); }

    @Override
    public StateGraph graph() throws GraphStateException {
        var graph = new StateGraph(id(), () -> Map.of("target", new ReplaceStrategy(),
                "maxRounds", new ReplaceStrategy(), "score", new ReplaceStrategy(),
                "rounds", new ReplaceStrategy(), "reason", new ReplaceStrategy()))
                .addNode("initialize", node_async(state -> {
                    int target = integer(state, "target", 0, Integer.MAX_VALUE);
                    int maxRounds = integer(state, "maxRounds", 1, 20);
                    return Map.of("target", target, "maxRounds", maxRounds, "score", 0, "rounds", 0);
                }))
                .addNode("improve", node_async(state -> Map.of(
                        "score", state.<Integer>value("score").orElseThrow() + 2,
                        "rounds", state.<Integer>value("rounds").orElseThrow() + 1)))
                .addNode("finish", node_async(state -> Map.of("reason", reached(state)
                        ? "TARGET_REACHED" : "MAX_ROUNDS")))
                .addEdge(StateGraph.START, "initialize");
        // Both entry and loop-back inspect the same business termination rule.
        graph.addConditionalEdges("initialize", edge_async(this::next),
                Map.of("continue", "improve", "stop", "finish"));
        graph.addConditionalEdges("improve", edge_async(this::next),
                Map.of("continue", "improve", "stop", "finish"));
        return graph.addEdge("finish", StateGraph.END);
    }

    private String next(OverAllState state) {
        return reached(state) || state.<Integer>value("rounds").orElseThrow()
                >= state.<Integer>value("maxRounds").orElseThrow() ? "stop" : "continue";
    }

    private boolean reached(OverAllState state) {
        return state.<Integer>value("score").orElseThrow() >= state.<Integer>value("target").orElseThrow();
    }

    private int integer(OverAllState state, String key, int minimum, int maximum) {
        Object input = state.value(key).orElse(null);
        if (!(input instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw new IllegalArgumentException(key + " 必须是 " + minimum + " 到 " + maximum + " 的整数");
        }
        return number.intValue();
    }
}
