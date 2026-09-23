package com.xb.graph;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class GraphApiTest {
    @Autowired
    private MockMvc mvc;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void exposesAllEighteenLessonsWithoutAnApiKey() throws Exception {
        mvc.perform(get("/api/demos")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(18)));
    }

    @Test
    void executesHelloGraphWithTheRequestInput() throws Exception {
        mvc.perform(post("/api/demos/demo01/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"name\":\"小北\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.greeting").value("你好，小北！欢迎学习 Graph。"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void refusesUndeclaredStateAndMalformedApproval() throws Exception {
        mvc.perform(post("/api/demos/demo01/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"approved\":true}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/executions/missing/resume").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns404ForUnknownLessonAndExecution() throws Exception {
        mvc.perform(get("/api/demos/missing/graph")).andExpect(status().isNotFound());
        mvc.perform(get("/api/executions/missing")).andExpect(status().isNotFound());
    }

    @Test
    void requiresAJsonBooleanAndRejectsUnknownRequestFields() throws Exception {
        mvc.perform(post("/api/executions/missing/resume").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":\"true\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/demos/demo01/run").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{},\"approved\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedJsonWithoutLeakingAnException() throws Exception {
        mvc.perform(post("/api/demos/demo01/run").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
    }

    @Test
    void servesOrderedNodeEventsAndCompletionOverRealHttp() throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + "/api/demos/demo11/stream"))
                .timeout(java.time.Duration.ofSeconds(10)).build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
            var events = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for (String line : response.body().lines().filter(text -> text.startsWith("data:")).toList()) {
                events.add(mapper.readTree(line.substring(5)));
            }
            org.assertj.core.api.Assertions.assertThat(events.stream()
                            .filter(event -> event.path("type").asText().equals("node"))
                            .map(event -> event.path("node").asText()))
                    .containsExactly("__START__", "outline", "draft", "publish", "__END__");
            org.assertj.core.api.Assertions.assertThat(events.getLast().path("type").asText()).isEqualTo("result");
            org.assertj.core.api.Assertions.assertThat(events.getLast().path("status").asText()).isEqualTo("COMPLETED");
        }
    }
}
