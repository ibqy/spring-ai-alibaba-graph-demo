package com.xb.graph.demo09;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Component
public class IsolationGraphDemo implements GraphDemo {
    @Override
    public String id() { return "demo09"; }

    @Override
    public String title() { return "会话隔离：同图不同 threadId"; }

    @Override
    public String level() { return "advanced"; }

    @Override
    public Map<String, Object> sampleInput() { return Map.of("name", "小北", "message", "你好"); }

    @Override
    public StateGraph graph() throws GraphStateException {
        return new StateGraph(id(), () -> Map.of("name", new ReplaceStrategy(),
                "message", new ReplaceStrategy(), "messages", new AppendStrategy(),
                "turns", new ReplaceStrategy(), "reply", new ReplaceStrategy()))
                .addNode("record", node_async(state -> {
                    Object name = state.value("name").orElse("");
                    Object message = state.value("message").orElse("");
                    if (!(name instanceof String user) || user.isBlank()
                            || !(message instanceof String text) || text.isBlank()) {
                        throw new IllegalArgumentException("name 和 message 必须是非空字符串");
                    }
                    return Map.of("name", user.strip(), "message", text.strip(),
                            "messages", List.of(user.strip() + ":" + text.strip()));
                }))
                .addNode("reply", node_async(state -> {
                    int turns = state.<List<String>>value("messages").orElseThrow().size();
                    return Map.of("turns", turns, "reply", state.<String>value("name").orElseThrow()
                            + "，你的第" + turns + "条消息：" + state.<String>value("message").orElseThrow());
                }))
                .addEdge(StateGraph.START, "record")
                .addEdge("record", "reply")
                .addEdge("reply", StateGraph.END);
    }
}
