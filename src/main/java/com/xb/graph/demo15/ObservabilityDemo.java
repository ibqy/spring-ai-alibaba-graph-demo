package com.xb.graph.demo15;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class ObservabilityDemo implements GraphDemo {
    @Override
    public String id() { return "demo15"; }

    @Override
    public String title() { return "实际节点轨迹、耗时与失败观测"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("task", "检查教学订单", "fail", false); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(() -> Map.of("trace", new AppendStrategy(), "observations", new AppendStrategy()))
                .addNode("prepare", observed("prepare", state -> {
                    Object task = state.value("task").orElse("");
                    if (!(task instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("task 必须是非空字符串");
                    }
                    if (!(state.value("fail").orElse(false) instanceof Boolean)) {
                        throw new IllegalArgumentException("fail 必须是 Boolean");
                    }
                    return Map.of("normalizedTask", text.strip());
                }))
                .addNode("work", observed("work", state -> {
                    if (Boolean.TRUE.equals(state.value("fail").orElse(false))) {
                        throw new IllegalStateException("教学模拟：业务节点执行失败");
                    }
                    return Map.of("result", "已处理：" + state.value("normalizedTask").orElseThrow());
                }))
                .addNode("report", observed("report", state -> Map.of("outcome", "COMPLETED")))
                .addNode("failure_report", observed("failure_report", state -> Map.of(
                        "outcome", "FAILED", "result", "失败已记录，未将业务失败伪装为成功")))
                .addEdge(StateGraph.START, "prepare")
                .addConditionalEdges("prepare", edge_async(state ->
                                Boolean.TRUE.equals(state.value("nodeFailed").orElse(false)) ? "failed" : "ready"),
                        Map.of("failed", "failure_report", "ready", "work"))
                .addConditionalEdges("work", edge_async(state ->
                                Boolean.TRUE.equals(state.value("nodeFailed").orElse(false)) ? "failed" : "done"),
                        Map.of("failed", "failure_report", "done", "report"))
                .addEdge("report", StateGraph.END)
                .addEdge("failure_report", StateGraph.END);
    }

    /** 计量真实 NodeAction 调用，不把合成数字当作遥测，不配置任何监控后端。 */
    private static AsyncNodeAction observed(String node, NodeAction action) {
        return node_async(state -> {
            long started = System.nanoTime();
            var delta = new LinkedHashMap<String, Object>();
            var observation = new LinkedHashMap<String, Object>();
            observation.put("node", node);
            boolean failed = false;
            try {
                delta.putAll(action.apply(state));
                observation.put("status", "SUCCEEDED");
            } catch (Exception exception) {
                failed = true;
                observation.put("status", "FAILED");
                observation.put("errorType", exception.getClass().getSimpleName());
                // 只暴露固定的教学错误信息，不将任意异常消息或输入写进观测记录。
                delta.put("error", "节点 " + node + " 执行失败");
            }
            long elapsed = System.nanoTime() - started;
            observation.put("elapsedNanos", elapsed);
            long previousElapsed = "prepare".equals(node) ? 0 : state.<Number>value("observedElapsedNanos", 0L).longValue();
            int previousFailures = "prepare".equals(node) ? 0 : state.<Number>value("failedNodes", 0).intValue();
            delta.put("observedElapsedNanos", previousElapsed + elapsed);
            delta.put("failedNodes", previousFailures + (failed ? 1 : 0));
            delta.put("nodeFailed", failed);
            delta.put("observations", List.of(observation));
            delta.put("trace", List.of(node));
            return delta;
        });
    }
}
