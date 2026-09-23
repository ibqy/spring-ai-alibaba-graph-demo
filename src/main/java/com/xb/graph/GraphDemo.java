package com.xb.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import java.util.Map;
import java.util.Set;

public interface GraphDemo {
    String id();

    String title();

    String level();

    Map<String, Object> sampleInput();

    StateGraph graph() throws GraphStateException;

    default Set<String> reviewNodes() {
        return Set.of();
    }
}
