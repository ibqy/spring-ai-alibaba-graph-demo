package com.xb.graph.web;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.xb.graph.GraphDemo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GraphRuntime {
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_SESSIONS = 100;
    private final Map<String, GraphDemo> demos;
    private final Map<String, Session> sessions = Collections.synchronizedMap(new LinkedHashMap<>());

    public GraphRuntime(List<GraphDemo> demos) {
        this.demos = demos.stream().collect(Collectors.toUnmodifiableMap(GraphDemo::id, Function.identity()));
    }

    public List<DemoDescription> catalog() {
        return demos.values().stream().sorted(Comparator.comparing(GraphDemo::id))
                .map(d -> new DemoDescription(d.id(), d.title(), d.level(), d.sampleInput(),
                        !d.reviewNodes().isEmpty())).toList();
    }

    public String diagram(String demoId) {
        try {
            return demo(demoId).graph().compile().getGraph(GraphRepresentation.Type.MERMAID).content();
        } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException error) {
            throw new IllegalStateException("图定义无法编译", error);
        }
    }

    public ExecutionResult run(String demoId, Map<String, Object> input) {
        Session session = createSession(demoId, input);
        synchronized (session) {
            consume(session, session.input, session.config);
            return session.result;
        }
    }

    public ExecutionResult resume(String executionId, boolean approved) {
        Session session = session(executionId);
        synchronized (session) {
            if (!session.result.status().equals("WAITING_REVIEW")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "只有等待审核的执行可以恢复，完成后不能重复审批");
            }
            try {
                var latest = RunnableConfig.builder().threadId(executionId).build();
                var updated = session.graph.updateState(latest, Map.of("approved", approved));
                session.config = updated.withResume();
                session.result = snapshot(session, "RUNNING");
                consume(session, null, session.config);
                return session.result;
            } catch (RuntimeException error) {
                session.result = snapshot(session, "FAILED");
                throw error;
            } catch (Exception error) {
                session.result = snapshot(session, "FAILED");
                throw new IllegalStateException("恢复检查点失败", error);
            }
        }
    }

    public ExecutionResult result(String executionId) {
        return session(executionId).result;
    }

    public List<CheckpointView> history(String executionId) {
        Session session = session(executionId);
        synchronized (session) {
            if (session.result.status().equals("RUNNING")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "执行中不能读取检查点历史，请在暂停或结束后查询");
            }
            return session.graph.getStateHistory(RunnableConfig.builder().threadId(executionId).build())
                    .stream().map(s -> new CheckpointView(s.node(), s.next(), copy(s.state().data()))).toList();
        }
    }

    public Flux<ExecutionEvent> stream(String demoId) {
        demo(demoId);
        return Flux.defer(() -> {
            Session session = createSession(demoId, Map.of());
            return session.graph.stream(session.input, session.config)
                    // Graph 1.1.2.2 的内部 flatMap 在背压下重排事件；先按产生顺序有界缓冲。
                    .onBackpressureBuffer(256)
                    .filter(output -> !(output instanceof InterruptionMetadata))
                    .timeout(RUN_TIMEOUT)
                    .map(output -> {
                        capture(session, output);
                        return new ExecutionEvent("node", session.id, output.node(), "RUNNING", session.state);
                    })
                    .concatWith(Mono.fromSupplier(() -> {
                        finish(session);
                        return new ExecutionEvent("result", session.id, null, session.result.status(), session.state);
                    }))
                    .onErrorResume(error -> {
                        session.result = snapshot(session, "FAILED");
                        return Mono.just(new ExecutionEvent("error", session.id, null, "FAILED", Map.of()));
                    })
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL && session.result.status().equals("RUNNING")) {
                            session.result = snapshot(session, "CANCELLED");
                        }
                    });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Session createSession(String demoId, Map<String, Object> input) {
        GraphDemo demo = demo(demoId);
        Map<String, Object> merged = validatedInput(demo, input);
        try {
            var config = CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                    .interruptsBefore(demo.reviewNodes())
                    .recursionLimit(100)
                    .build();
            var session = new Session(UUID.randomUUID().toString(), demo, demo.graph().compile(config), merged);
            synchronized (sessions) {
                if (sessions.size() >= MAX_SESSIONS) {
                    String oldestFinished = sessions.entrySet().stream()
                            .filter(e -> !e.getValue().result.status().equals("RUNNING")
                                    && !e.getValue().result.status().equals("WAITING_REVIEW"))
                            .map(Map.Entry::getKey).findFirst().orElseThrow(() ->
                                    new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "运行记录已满，请完成待审核流程或重启教学服务"));
                    sessions.remove(oldestFinished);
                }
                sessions.put(session.id, session);
            }
            return session;
        } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException error) {
            throw new IllegalStateException("图定义无法编译", error);
        }
    }

    private void consume(Session session, Map<String, Object> input, RunnableConfig config) {
        try {
            session.graph.stream(input, config)
                    .filter(output -> !(output instanceof InterruptionMetadata))
                    .doOnNext(output -> capture(session, output))
                    .timeout(RUN_TIMEOUT).blockLast();
            finish(session);
        } catch (RuntimeException error) {
            session.result = snapshot(session, "FAILED");
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw error;
        }
    }

    private void capture(Session session, NodeOutput output) {
        session.state = copy(output.state().data());
        if (!output.isSTART() && !output.isEND()) {
            session.visited.add(output.node());
        }
        session.ended = output.isEND() && !output.isSubGraph();
    }

    private void finish(Session session) {
        if (session.ended) {
            session.result = snapshot(session, "COMPLETED");
            return;
        }
        var config = RunnableConfig.builder().threadId(session.id).build();
        var checkpoint = session.graph.getState(config);
        if (checkpoint.next() != null && session.demo.reviewNodes().contains(checkpoint.next())) {
            session.result = snapshot(session, "WAITING_REVIEW");
            return;
        }
        throw new IllegalStateException("图未到达 END 或审核点，请检查循环与递归上限");
    }

    private ExecutionResult snapshot(Session session, String status) {
        return new ExecutionResult(session.id, session.demo.id(), status, session.state, List.copyOf(session.visited));
    }

    private GraphDemo demo(String id) {
        GraphDemo demo = demos.get(id);
        if (demo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "示例不存在");
        }
        return demo;
    }

    private Session session(String id) {
        Session session = sessions.get(id);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行不存在、已过期或服务已重启");
        }
        return session;
    }

    private Map<String, Object> validatedInput(GraphDemo demo, Map<String, Object> input) {
        var merged = new LinkedHashMap<>(demo.sampleInput());
        input.forEach((key, value) -> {
            if (!merged.containsKey(key)) {
                throw new IllegalArgumentException("不支持的输入字段：" + key);
            }
            Object sample = merged.get(key);
            if (sample instanceof String) {
                if (!(value instanceof String text) || text.isBlank() || text.length() > 8000) {
                    throw new IllegalArgumentException(key + " 必须是 1–8000 字符的非空文本");
                }
            } else if (sample instanceof Boolean && !(value instanceof Boolean)) {
                throw new IllegalArgumentException(key + " 必须是布尔值");
            } else if (sample instanceof Number) {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw new IllegalArgumentException(key + " 必须是有限数字");
                }
                if (sample instanceof Integer) {
                    double numeric = number.doubleValue();
                    if (numeric != Math.rint(numeric) || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(key + " 必须是 int 范围内的整数");
                    }
                    value = number.intValue();
                }
            }
            merged.put(key, value);
        });
        return merged;
    }

    private static Map<String, Object> copy(Map<String, Object> state) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    public record DemoDescription(String id, String title, String level, Map<String, Object> sampleInput,
                                  boolean requiresReview) { }

    public record ExecutionResult(String executionId, String demoId, String status, Map<String, Object> state,
                                  List<String> visitedNodes) { }

    public record CheckpointView(String node, String next, Map<String, Object> state) { }

    public record ExecutionEvent(String type, String executionId, String node, String status,
                                 Map<String, Object> state) { }

    private static final class Session {
        private final String id;
        private final GraphDemo demo;
        private final CompiledGraph graph;
        private final Map<String, Object> input;
        private final List<String> visited = new ArrayList<>();
        private RunnableConfig config;
        private Map<String, Object> state;
        private boolean ended;
        private volatile ExecutionResult result;

        private Session(String id, GraphDemo demo, CompiledGraph graph, Map<String, Object> input) {
            this.id = id;
            this.demo = demo;
            this.graph = graph;
            this.input = input;
            this.config = RunnableConfig.builder().threadId(id).build();
            this.state = copy(input);
            this.result = new ExecutionResult(id, demo.id(), "RUNNING", this.state, List.of());
        }
    }
}
