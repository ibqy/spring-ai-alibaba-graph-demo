package com.xb.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.xb.graph.web.GraphRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRuntimeTest {
    @Test
    void runsSampleAndRetainsCheckpointHistory() throws Exception {
        var runtime = new GraphRuntime(List.of(new ReviewDemo()));
        var result = runtime.run("test", Map.of("name", "小北"));
        assertThat(result.status()).isEqualTo("WAITING_REVIEW");
        assertThat(result.state()).containsEntry("draft", "小北的草稿");
        assertThat(result.state()).doesNotContainKey("published");
        assertThat(result.visitedNodes()).containsExactly("prepare");
        assertThat(runtime.history(result.executionId())).isNotEmpty();
        assertThat(runtime.diagram("test")).contains("review");
    }

    @Test
    void rejectsStateInjectionAndInvalidInputTypes() throws Exception {
        var runtime = new GraphRuntime(List.of(new ReviewDemo()));
        assertThatThrownBy(() -> runtime.run("test", Map.of("approved", true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.run("test", Map.of("name", 12)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.run("test", Map.of("name", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resumesOnlyTheSelectedExecutionAndCannotApproveTwice() throws Exception {
        var demo = new ReviewDemo();
        var runtime = new GraphRuntime(List.of(demo));
        var first = runtime.run("test", Map.of("name", "甲"));
        var second = runtime.run("test", Map.of("name", "乙"));
        var approved = runtime.resume(first.executionId(), true);
        assertThat(approved.status()).isEqualTo("COMPLETED");
        assertThat(approved.state()).containsEntry("published", "甲的草稿");
        assertThat(runtime.result(second.executionId()).status()).isEqualTo("WAITING_REVIEW");
        assertThat(runtime.resume(second.executionId(), false).state()).containsEntry("rejected", true);
        assertThat(demo.writes.get()).isEqualTo(1);
        assertThatThrownBy(() -> runtime.resume(first.executionId(), true))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void concurrentApprovalsExecuteTheSideEffectOnce() throws Exception {
        var demo = new ReviewDemo();
        var runtime = new GraphRuntime(List.of(demo));
        var initial = runtime.run("test", Map.of());
        var approvals = List.of(
                CompletableFuture.supplyAsync(() -> approve(runtime, initial.executionId())),
                CompletableFuture.supplyAsync(() -> approve(runtime, initial.executionId())));
        assertThat(approvals.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count())
                .isEqualTo(1);
        assertThat(demo.writes.get()).isEqualTo(1);
    }

    @Test
    void streamsActualNodesAndAReviewResult() throws Exception {
        var runtime = new GraphRuntime(List.of(new ReviewDemo()));
        var events = runtime.stream("test").collectList().block();
        assertThat(events).isNotNull();
        assertThat(events.stream().filter(e -> e.type().equals("node")).map(e -> e.node()))
                .contains("prepare").doesNotContain("publish");
        assertThat(events.stream().filter(e -> e.type().equals("node") && "prepare".equals(e.node())).count())
                .isEqualTo(1);
        assertThat(events.getLast().type()).isEqualTo("result");
        assertThat(events.getLast().status()).isEqualTo("WAITING_REVIEW");
    }

    @Test
    void missingDemoAndExecutionReturnNotFound() throws Exception {
        var runtime = new GraphRuntime(List.of(new ReviewDemo()));
        assertThatThrownBy(() -> runtime.run("missing", Map.of())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> runtime.resume("missing", true)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resumesTheLatestCheckpointAcrossTwoReviewStages() throws Exception {
        var demo = new ReviewDemo() {
            @Override public Set<String> reviewNodes() { return Set.of("review", "final_review"); }

            @Override public StateGraph graph() throws GraphStateException {
                return new StateGraph(() -> Map.of("approved", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE))
                        .addNode("prepare", node_async(s -> Map.of()))
                        .addNode("review", node_async(s -> Map.of()))
                        .addNode("publish", node_async(s -> { writes.incrementAndGet(); return Map.of(); }))
                        .addNode("final_review", node_async(s -> Map.of()))
                        .addEdge(START, "prepare").addEdge("prepare", "review")
                        .addEdge("review", "publish").addEdge("publish", "final_review")
                        .addEdge("final_review", END);
            }
        };
        var runtime = new GraphRuntime(List.of(demo));
        var initial = runtime.run("test", Map.of());
        assertThat(runtime.resume(initial.executionId(), true).status()).isEqualTo("WAITING_REVIEW");
        assertThat(runtime.resume(initial.executionId(), true).status()).isEqualTo("COMPLETED");
        assertThat(demo.writes.get()).isEqualTo(1);
    }

    @Test
    void recursionExhaustionIsFailureRatherThanCompletion() {
        var demo = new ReviewDemo() {
            @Override public Set<String> reviewNodes() { return Set.of(); }

            @Override public StateGraph graph() throws GraphStateException {
                return new StateGraph(() -> Map.of("round", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE))
                        .addNode("tick", node_async(s -> Map.of("round", s.value("round", 0) + 1)))
                        .addEdge(START, "tick")
                        .addConditionalEdges("tick", edge_async(s -> s.value("round", 0) < 200 ? "again" : "done"),
                                Map.of("again", "tick", "done", END));
            }
        };
        var runtime = new GraphRuntime(List.of(demo));
        assertThatThrownBy(() -> runtime.run("test", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("END");
        var events = runtime.stream("test").collectList().block();
        assertThat(events).isNotNull();
        assertThat(events.getLast().type()).isEqualTo("error");
        assertThat(events.getLast().status()).isEqualTo("FAILED");
    }

    @Test
    void rejectsHistoryWhileStreamingAndProvidesItAfterCompletion() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executionId = new java.util.concurrent.atomic.AtomicReference<String>();
        var demo = new ReviewDemo() {
            @Override public Set<String> reviewNodes() { return Set.of(); }

            @Override public StateGraph graph() throws GraphStateException {
                return new StateGraph(() -> Map.of("done", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE))
                        .addNode("wait", node_async(s -> {
                            entered.countDown();
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test gate timed out");
                            }
                            return Map.of("done", true);
                        }))
                        .addEdge(START, "wait").addEdge("wait", END);
            }
        };
        var runtime = new GraphRuntime(List.of(demo));
        var pending = runtime.stream("test").doOnNext(event -> executionId.set(event.executionId()))
                .collectList().toFuture();
        try {
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(executionId.get()).isNotNull();
            assertThatThrownBy(() -> runtime.history(executionId.get()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        } finally {
            release.countDown();
        }
        assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS).getLast().status()).isEqualTo("COMPLETED");
        assertThat(runtime.history(executionId.get())).isNotEmpty();
    }

    @Test
    void retainsWaitingExecutionsAndEvictsOnlyTerminalRecordsAtCapacity() {
        var runtime = new GraphRuntime(List.of(new ReviewDemo()));
        var first = runtime.run("test", Map.of());
        for (int index = 1; index < 100; index++) runtime.run("test", Map.of());
        assertThatThrownBy(() -> runtime.run("test", Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        runtime.resume(first.executionId(), false);
        assertThat(runtime.run("test", Map.of()).status()).isEqualTo("WAITING_REVIEW");
        assertThatThrownBy(() -> runtime.result(first.executionId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    private boolean approve(GraphRuntime runtime, String id) {
        try {
            runtime.resume(id, true);
            return true;
        } catch (ResponseStatusException alreadyCompleted) {
            return false;
        }
    }

    static class ReviewDemo implements GraphDemo {
        final AtomicInteger writes = new AtomicInteger();

        public String id() { return "test"; }
        public String title() { return "审批测试"; }
        public String level() { return "test"; }
        public Map<String, Object> sampleInput() { return Map.of("name", "学习者"); }
        public Set<String> reviewNodes() { return Set.of("review"); }

        public StateGraph graph() throws GraphStateException {
            return new StateGraph(() -> Map.of(
                    "name", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE,
                    "draft", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE,
                    "approved", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE,
                    "published", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE,
                    "rejected", com.alibaba.cloud.ai.graph.KeyStrategy.REPLACE))
                    .addNode("prepare", node_async(s -> Map.of("draft", s.value("name", "学习者") + "的草稿")))
                    .addNode("review", node_async(s -> Map.of()))
                    .addNode("publish", node_async(s -> {
                        writes.incrementAndGet();
                        return Map.of("published", s.value("draft").orElseThrow());
                    }))
                    .addNode("reject", node_async(s -> Map.of("rejected", true)))
                    .addEdge(START, "prepare")
                    .addEdge("prepare", "review")
                    .addConditionalEdges("review", edge_async(s -> s.value("approved", false) ? "yes" : "no"),
                            Map.of("yes", "publish", "no", "reject"))
                    .addEdge("publish", END)
                    .addEdge("reject", END);
        }
    }
}
