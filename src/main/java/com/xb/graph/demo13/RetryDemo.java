package com.xb.graph.demo13;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class RetryDemo implements GraphDemo {
    private static final int MAX_ATTEMPTS = 3;

    @Override
    public String id() { return "demo13"; }

    @Override
    public String title() { return "条件回边重试与耗尽补偿"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("failuresBeforeSuccess", 2); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(() -> Map.of("trace", new AppendStrategy()))
                .addNode("prepare", node_async(state -> {
                    int failures = failureCount(state.value("failuresBeforeSuccess").orElse(0));
                    return Map.of("failuresBeforeSuccess", failures, "attempts", 0, "succeeded", false,
                            "compensated", false, "lastError", "", "trace", List.of("prepare"));
                }))
                .addNode("attempt", node_async(state -> {
                    int attempt = state.<Integer>value("attempts").orElseThrow() + 1;
                    int failures = state.<Integer>value("failuresBeforeSuccess").orElseThrow();
                    try {
                        simulatedBusinessCall(attempt, failures);
                        return Map.of("attempts", attempt, "succeeded", true, "lastError", "",
                                "trace", List.of("attempt:" + attempt + ":succeeded"));
                    } catch (TransientBusinessException exception) {
                        // 仅将明确可重试的业务错误写回状态，未知异常仍应终止执行。
                        return Map.of("attempts", attempt, "succeeded", false, "lastError", exception.getMessage(),
                                "trace", List.of("attempt:" + attempt + ":failed"));
                    }
                }))
                .addNode("success", node_async(state -> Map.of("outcome", "SUCCEEDED",
                        "result", "模拟业务调用成功", "trace", List.of("success"))))
                .addNode("compensate", node_async(state -> Map.of("outcome", "COMPENSATED",
                        "compensated", true, "result", "尝试耗尽，已模拟释放预留资源",
                        "trace", List.of("compensate"))))
                .addEdge(StateGraph.START, "prepare")
                .addEdge("prepare", "attempt")
                .addConditionalEdges("attempt", edge_async(state -> {
                    if (Boolean.TRUE.equals(state.value("succeeded").orElse(false))) return "done";
                    return state.<Integer>value("attempts").orElseThrow() < MAX_ATTEMPTS ? "retry" : "exhausted";
                }), Map.of("done", "success", "retry", "attempt", "exhausted", "compensate"))
                .addEdge("success", StateGraph.END)
                .addEdge("compensate", StateGraph.END);
    }

    private static int failureCount(Object value) {
        try {
            if (!(value instanceof Number number)) throw new NumberFormatException();
            int count = new BigDecimal(number.toString()).intValueExact();
            if (count < 0) throw new NumberFormatException();
            return count;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("failuresBeforeSuccess 必须是非负整数，且不超过 2147483647");
        }
    }

    private static void simulatedBusinessCall(int attempt, int failures) {
        if (attempt <= failures) throw new TransientBusinessException("教学模拟：第 " + attempt + " 次暂时失败");
    }

    private static final class TransientBusinessException extends RuntimeException {
        private TransientBusinessException(String message) { super(message); }
    }
}
