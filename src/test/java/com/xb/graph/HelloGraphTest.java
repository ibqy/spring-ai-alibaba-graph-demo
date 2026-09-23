package com.xb.graph;

import com.xb.graph.demo01.HelloGraphDemo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HelloGraphTest {
    @Test
    void greetsTheProvidedLearnerThroughTheGraph() throws Exception {
        var graph = new HelloGraphDemo().graph().compile();
        var state = graph.invoke(Map.of("name", "小北")).orElseThrow();
        assertThat(state.<String>value("greeting")).contains("你好，小北！欢迎学习 Graph。");
    }
}
