package com.xb.graph.demo14;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class IdempotencyDemo implements GraphDemo {
    // 属于单个 Spring 单例，跨执行复用；不是持久化账本，不执行真实支付。
    private final ConcurrentHashMap<String, Receipt> receipts = new ConcurrentHashMap<>();
    private final AtomicInteger effects = new AtomicInteger();

    @Override
    public String id() { return "demo14"; }

    @Override
    public String title() { return "并发安全的模拟副作用幂等"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() {
        return Map.of("idempotencyKey", "lesson-order-001", "payload", "教学积分+10");
    }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(() -> Map.of("trace", new AppendStrategy()))
                .addNode("validate", node_async(state -> {
                    for (String field : List.of("idempotencyKey", "payload")) {
                        Object value = state.value(field).orElse("");
                        if (!(value instanceof String text) || text.isBlank()) {
                            throw new IllegalArgumentException(field + " 必须是非空字符串");
                        }
                    }
                    return Map.of("simulation", true, "trace", List.of("validate"));
                }))
                .addNode("apply", node_async(state -> {
                    String key = state.<String>value("idempotencyKey").orElseThrow();
                    String payload = state.<String>value("payload").orElseThrow();
                    var created = new AtomicBoolean(false);
                    Receipt receipt = receipts.computeIfAbsent(key, ignored -> {
                        // 唯一副作用是内存计数加一与生成回执，回调不访问任何外部服务。
                        int sequence = effects.incrementAndGet();
                        created.set(true);
                        return new Receipt(payload, "sim-effect-" + sequence);
                    });
                    boolean conflict = !receipt.payload().equals(payload);
                    String outcome = conflict ? "CONFLICT" : created.get() ? "APPLIED" : "REPLAYED";
                    return Map.of("outcome", outcome, "effectId", receipt.effectId(),
                            "replayed", !conflict && !created.get(), "totalEffects", effects.get(),
                            "trace", List.of("apply"));
                }))
                .addNode("report", node_async(state -> {
                    String outcome = state.<String>value("outcome").orElseThrow();
                    String message = switch (outcome) {
                        case "APPLIED" -> "首次请求，已创建内存模拟回执";
                        case "REPLAYED" -> "相同请求，复用已有回执，未重复执行副作用";
                        default -> "幂等冲突：同一 key 对应不同 payload，未执行新副作用";
                    };
                    return Map.of("message", message, "trace", List.of("report"));
                }))
                .addEdge(StateGraph.START, "validate")
                .addEdge("validate", "apply")
                .addEdge("apply", "report")
                .addEdge("report", StateGraph.END);
    }

    private record Receipt(String payload, String effectId) { }
}
