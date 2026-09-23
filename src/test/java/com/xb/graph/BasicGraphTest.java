package com.xb.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.xb.graph.demo01.HelloGraphDemo;
import com.xb.graph.demo02.SequenceGraphDemo;
import com.xb.graph.demo03.StateMergeGraphDemo;
import com.xb.graph.demo04.ConditionalGraphDemo;
import com.xb.graph.demo05.LoopGraphDemo;
import com.xb.graph.demo06.ParallelGraphDemo;
import com.xb.graph.demo07.SubgraphDemo;
import com.xb.graph.demo08.CheckpointGraphDemo;
import com.xb.graph.demo09.IsolationGraphDemo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BasicGraphTest {
    static Stream<GraphDemo> demos() {
        return Stream.of(new HelloGraphDemo(), new SequenceGraphDemo(), new StateMergeGraphDemo(),
                new ConditionalGraphDemo(), new LoopGraphDemo(), new ParallelGraphDemo(),
                new SubgraphDemo(), new CheckpointGraphDemo(), new IsolationGraphDemo());
    }

    @ParameterizedTest
    @MethodSource("demos")
    void everyDemoIsDiscoverableAndRunsItsScalarSample(GraphDemo demo) throws Exception {
        assertThat(demo.getClass().isAnnotationPresent(Component.class)).isTrue();
        assertThat(demo.id()).matches("demo0[1-9]");
        assertThat(demo.level()).isIn("basic", "advanced");
        assertThat(demo.title()).isNotBlank();
        assertThat(demo.reviewNodes()).isEmpty();
        assertThat(demo.sampleInput().values()).allMatch(value ->
                value instanceof String || value instanceof Boolean || value instanceof Number);
        Map<String, Object> expected = switch (demo.id()) {
            case "demo01" -> Map.of("greeting", "你好，小北！欢迎学习 Graph。");
            case "demo02" -> Map.of("cleaned", "hello Graph", "result", "HELLO GRAPH");
            case "demo03" -> Map.of("status", "published", "events", List.of("draft:Graph", "published:Graph"));
            case "demo04" -> Map.of("passed", true, "result", "通过");
            case "demo05" -> Map.of("score", 6, "rounds", 3, "reason", "TARGET_REACHED");
            case "demo06" -> Map.of("characters", 15, "words", 3, "uppercase", "SPRING AI GRAPH",
                    "result", "characters=15 | uppercase=SPRING AI GRAPH | words=3");
            case "demo07" -> Map.of("cleanedName", "小北", "greeting", "子图你好，小北！", "result", "[子图你好，小北！]");
            case "demo08" -> Map.of("normalized", "Graph", "length", 5, "summary", "Graph (5)");
            case "demo09" -> Map.of("turns", 1, "messages", List.of("小北:你好"), "reply", "小北，你的第1条消息：你好");
            default -> throw new AssertionError("缺少手工推导的样例断言：" + demo.id());
        };
        assertThat(demo.graph().compile().invoke(demo.sampleInput()).orElseThrow().data())
                .containsAllEntriesOf(expected);
    }

    @Test
    void helloUsesTheActualNameAndRejectsBlankNames() throws Exception {
        var graph = new HelloGraphDemo().graph().compile();
        assertThat(run(graph, Map.of("name", " 小南 ")).data())
                .containsEntry("greeting", "你好，小南！欢迎学习 Graph。");
        assertThatThrownBy(() -> graph.invoke(Map.of("name", "  ")))
                .hasStackTraceContaining("name 不能为空");
    }

    @Test
    void sequenceCleansBeforeProcessingWithoutDestroyingInput() throws Exception {
        var graph = new SequenceGraphDemo().graph().compile();
        var outputs = graph.stream(Map.of("text", "  hello   Graph  ")).collectList().block();
        var clean = node(outputs, "clean").state();
        assertThat(clean.data()).containsEntry("cleaned", "hello Graph").doesNotContainKey("result");
        var process = node(outputs, "process").state();
        assertThat(process.data()).containsEntry("text", "  hello   Graph  ")
                .containsEntry("result", "HELLO GRAPH");
    }

    @Test
    void replaceKeepsLatestStatusWhileAppendKeepsBothEvents() throws Exception {
        var graph = new StateMergeGraphDemo().graph().compile();
        var outputs = graph.stream(Map.of("text", "Graph")).collectList().block();
        assertThat(node(outputs, "draft").state().data()).containsEntry("status", "draft")
                .containsEntry("events", List.of("draft:Graph"));
        assertThat(node(outputs, "publish").state().data()).containsEntry("status", "published")
                .containsEntry("events", List.of("draft:Graph", "published:Graph"));
    }

    @Test
    void conditionalRoutesBoundaryToPassAndLowerScoreToRevise() throws Exception {
        var graph = new ConditionalGraphDemo().graph().compile();
        var pass = graph.stream(Map.of("score", 60)).collectList().block();
        assertThat(pass).extracting(NodeOutput::node).contains("pass").doesNotContain("revise");
        assertThat(node(pass, "pass").state().data()).containsEntry("result", "通过");
        var revise = graph.stream(Map.of("score", 59)).collectList().block();
        assertThat(revise).extracting(NodeOutput::node).contains("revise").doesNotContain("pass");
        assertThat(node(revise, "revise").state().data()).containsEntry("result", "请修改");
        assertThatThrownBy(() -> graph.invoke(Map.of("score", 101)))
                .hasStackTraceContaining("score 必须是 0 到 100 的数字");
    }

    @Test
    void loopStopsWhenTargetIsReached() throws Exception {
        var state = run(new LoopGraphDemo().graph().compile(), Map.of("target", 6, "maxRounds", 5));
        assertThat(state.data()).containsEntry("score", 6).containsEntry("rounds", 3)
                .containsEntry("reason", "TARGET_REACHED");
    }

    @Test
    void loopStopsAtBusinessLimitRatherThanGraphRecursionLimit() throws Exception {
        var graph = new LoopGraphDemo().graph().compile();
        var outputs = graph.stream(Map.of("target", 100, "maxRounds", 2)).collectList().block();
        assertThat(outputs).filteredOn(output -> output.node().equals("improve")).hasSize(2);
        assertThat(node(outputs, "finish").state().data()).containsEntry("score", 4)
                .containsEntry("rounds", 2).containsEntry("reason", "MAX_ROUNDS");
        assertThat(run(graph, Map.of("target", 0, "maxRounds", 2)).data())
                .containsEntry("rounds", 0).containsEntry("reason", "TARGET_REACHED");
        assertThat(run(graph, Map.of("target", 100, "maxRounds", 20)).data())
                .containsEntry("rounds", 20).containsEntry("reason", "MAX_ROUNDS");
        assertThatThrownBy(() -> graph.invoke(Map.of("target", 6, "maxRounds", 0)))
                .hasStackTraceContaining("maxRounds 必须是 1 到 20 的整数");
        assertThatThrownBy(() -> graph.invoke(Map.of("target", 6, "maxRounds", 1.5)))
                .hasStackTraceContaining("maxRounds 必须是 1 到 20 的整数");
    }

    @Test
    @Timeout(15)
    void parallelDispatchesAllThreeBranchesBeforeJoiningAndMergesEveryResult() throws Exception {
        // If fan-out becomes sequential, the three-worker gate cannot open.
        var entered = new CountDownLatch(3);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var config = RunnableConfig.builder().defaultParallelExecutor(task -> workers.execute(() -> {
                entered.countDown();
                try {
                    if (!entered.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("三个并行分支没有同时派发");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                task.run();
            })).build();
            var outputs = new ParallelGraphDemo().graph().compile()
                    .stream(Map.of("text", "Spring AI Graph"), config).collectList().block();
            assertThat(entered.getCount()).isZero();
            assertThat(outputs).filteredOn(output -> output.node().equals("aggregate")).hasSize(1);
            var state = node(outputs, "aggregate").state();
            assertThat(state.data()).containsEntry("characters", 15).containsEntry("words", 3)
                    .containsEntry("uppercase", "SPRING AI GRAPH")
                    .containsEntry("result", "characters=15 | uppercase=SPRING AI GRAPH | words=3");
            assertThat(state.<List<String>>value("parts").orElseThrow())
                    .containsExactlyInAnyOrder("characters=15", "words=3", "uppercase=SPRING AI GRAPH");
        }
    }

    @Test
    void parentReceivesActualChildGraphUpdates() throws Exception {
        var state = run(new SubgraphDemo().graph().compile(), Map.of("name", " 小北 "));
        assertThat(state.data()).containsEntry("source", "parent").containsEntry("cleanedName", "小北")
                .containsEntry("greeting", "子图你好，小北！").containsEntry("result", "[子图你好，小北！]");
    }

    // #region checkpoint-history
    @Test
    void checkpointHistoryPreservesIntermediateStatesAndAllowsPinnedReads() throws Exception {
        var saver = new MemorySaver();
        var graph = new CheckpointGraphDemo().graph().compile(withSaver(saver));
        var config = thread("checkpoint-lesson");
        var outputs = graph.stream(Map.of("text", " Graph "), config).collectList().block();
        assertThat(node(outputs, "normalize").state().data())
                .containsEntry("normalized", "Graph").doesNotContainKeys("length", "summary");
        assertThat(node(outputs, "measure").state().data())
                .containsEntry("length", 5).doesNotContainKey("summary");
        assertThat(graph.getState(config).state().data()).containsEntry("summary", "Graph (5)");
        var history = graph.getStateHistory(config);
        assertThat(history).extracting(StateSnapshot::node).contains("normalize", "measure", "summarize");
        var normalized = history.stream().filter(snapshot -> snapshot.node().equals("normalize"))
                .findFirst().orElseThrow();
        assertThat(normalized.config().checkPointId()).isPresent();
        assertThat(graph.getState(normalized.config()).state().data())
                .containsEntry("normalized", "Graph").doesNotContainKeys("length", "summary");
        assertThat(history.stream().map(snapshot -> snapshot.config().checkPointId().orElseThrow()).toList())
                .doesNotHaveDuplicates();
        var freshProcessEquivalent = new CheckpointGraphDemo().graph().compile(withSaver(new MemorySaver()));
        assertThat(freshProcessEquivalent.getStateHistory(config)).isEmpty();
    }
    // #endregion checkpoint-history

    // #region session-isolation
    @Test
    void sameCompiledGraphKeepsThreadHistoriesSeparateAndContinuesOnlyMatchingThread() throws Exception {
        var graph = new IsolationGraphDemo().graph().compile(withSaver(new MemorySaver()));
        var alice = thread("alice");
        var bob = thread("bob");
        assertThat(graph.invoke(Map.of("name", "小北", "message", "你好"), alice).orElseThrow().data())
                .containsEntry("turns", 1).containsEntry("reply", "小北，你的第1条消息：你好");
        graph.invoke(Map.of("name", "小南", "message", "独立会话"), bob).orElseThrow();
        var second = graph.invoke(Map.of("name", "小北", "message", "继续"), alice).orElseThrow();
        assertThat(second.data()).containsEntry("turns", 2)
                .containsEntry("messages", List.of("小北:你好", "小北:继续"))
                .containsEntry("reply", "小北，你的第2条消息：继续");
        assertThat(graph.getState(bob).state().data()).containsEntry("turns", 1)
                .containsEntry("messages", List.of("小南:独立会话"));
        assertThat(graph.getStateHistory(alice)).allSatisfy(snapshot -> {
            assertThat(snapshot.config().threadId()).contains("alice");
            assertThat(snapshot.state().<List<String>>value("messages").orElse(List.of()))
                    .doesNotContain("小南:独立会话");
        });
        assertThat(graph.getStateHistory(bob)).allSatisfy(snapshot -> {
            assertThat(snapshot.config().threadId()).contains("bob");
            assertThat(snapshot.state().<List<String>>value("messages").orElse(List.of()))
                    .doesNotContain("小北:你好", "小北:继续");
        });
        assertThat(graph.getStateHistory(thread("unseen"))).isEmpty();
    }
    // #endregion session-isolation

    // #region checkpoint-config
    private static CompileConfig withSaver(MemorySaver saver) {
        return CompileConfig.builder().releaseThread(false)
                .saverConfig(SaverConfig.builder().register(saver).build()).build();
    }

    private static RunnableConfig thread(String id) {
        return RunnableConfig.builder().threadId(id).build();
    }
    // #endregion checkpoint-config

    private static OverAllState run(CompiledGraph graph, Map<String, Object> input) {
        return graph.invoke(input).orElseThrow();
    }

    private static NodeOutput node(List<NodeOutput> outputs, String name) {
        return outputs.stream().filter(output -> output.node().equals(name)).findFirst().orElseThrow();
    }
}
