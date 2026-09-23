package com.xb.graph.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GraphController {
    private final GraphRuntime runtime;

    public GraphController(GraphRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/demos")
    public List<GraphRuntime.DemoDescription> demos() {
        return runtime.catalog();
    }

    @GetMapping(value = "/demos/{demoId}/graph", produces = "text/plain;charset=UTF-8")
    public String graph(@PathVariable String demoId) {
        return runtime.diagram(demoId);
    }

    @PostMapping("/demos/{demoId}/run")
    public GraphRuntime.ExecutionResult run(@PathVariable String demoId, @Valid @RequestBody RunRequest request) {
        return runtime.run(demoId, request.input());
    }

    @GetMapping(value = "/demos/{demoId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<GraphRuntime.ExecutionEvent> stream(@PathVariable String demoId) {
        return runtime.stream(demoId);
    }

    @GetMapping("/executions/{executionId}")
    public GraphRuntime.ExecutionResult result(@PathVariable String executionId) {
        return runtime.result(executionId);
    }

    @GetMapping("/executions/{executionId}/history")
    public List<GraphRuntime.CheckpointView> history(@PathVariable String executionId) {
        return runtime.history(executionId);
    }

    @PostMapping("/executions/{executionId}/resume")
    public GraphRuntime.ExecutionResult resume(@PathVariable String executionId, @Valid @RequestBody ReviewRequest request) {
        return runtime.resume(executionId, request.approved());
    }

    public record RunRequest(@NotNull Map<String, Object> input) { }

    public record ReviewRequest(@NotNull Boolean approved) { }
}
