package com.xb.graph.demo17;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.xb.graph.GraphDemo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** 文本规则解析（非 OCR/LLM）与并行校验；ConcurrentHashMap 只模拟进程内台账。 */
@Component
public class ExpenseGraphDemo implements GraphDemo {
    private static final Map<String, Long> LIMITS = Map.of("交通", 50_000L, "餐饮", 20_000L, "住宿", 100_000L);
    private static final Pattern AMOUNT = fieldPattern("金额", "([+-]?\\d+(?:\\.\\d{1,2})?)\\s*(?:元)?");
    private static final Pattern CATEGORY = fieldPattern("类别", "([^;；\\n]+?)");
    private static final Pattern INVOICE = fieldPattern("发票号", "([A-Za-z0-9-]{1,40})");
    private final ConcurrentHashMap<String, Booking> ledger = new ConcurrentHashMap<>();
    private final Executor executor;

    @Autowired
    public ExpenseGraphDemo() {
        this(ForkJoinPool.commonPool());
    }

    /** 允许调用方提供受管理的 Executor；本组件不关闭调用方线程池。 */
    public ExpenseGraphDemo(Executor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override public String id() { return "demo17"; }
    @Override public String title() { return "报销并行校验、人工审批与幂等入账"; }
    @Override public String level() { return "practice"; }
    @Override public Set<String> reviewNodes() { return Set.of("review"); }
    @Override public Map<String, Object> sampleInput() {
        return Map.of("receipt", "金额=128.50;类别=交通;发票号=INV-001", "requestId", "EXP-DEMO-001");
    }

    @Override
    public StateGraph graph() throws GraphStateException {
        // node_async 只是同步适配；这两个节点显式提交 future，才有真实异步工作。
        AsyncNodeAction receiptCheck = s -> CompletableFuture.supplyAsync(() -> validateReceipt(s), executor);
        AsyncNodeAction limitCheck = s -> CompletableFuture.supplyAsync(() -> validateLimit(s), executor);
        return new StateGraph(() -> {
            Map<String, KeyStrategy> keys = new HashMap<>();
            for (String key : List.of("receipt", "requestId", "amountCents", "amountValid", "category", "invoiceId",
                    "extractionMode", "receiptValid", "limitValid", "receiptReason", "limitReason", "limitCents",
                    "validationPassed", "rejectionReason", "approved", "posted", "voucherId", "expenseStatus")) {
                keys.put(key, new ReplaceStrategy());
            }
            keys.put("trace", new AppendStrategy());
            return keys;
        })
                .addNode("extract", node_async(this::extract))
                .addNode("validate_receipt", receiptCheck)
                .addNode("validate_limit", limitCheck)
                .addNode("aggregate", node_async(this::aggregate))
                .addNode("review", node_async(s -> Map.of("trace", List.of("review"))))
                .addNode("reject", node_async(this::reject))
                .addNode("book", node_async(this::book))
                .addEdge(START, "extract")
                .addEdge("extract", "validate_receipt")
                .addEdge("extract", "validate_limit")
                .addEdge("validate_receipt", "aggregate")
                .addEdge("validate_limit", "aggregate")
                .addConditionalEdges("aggregate", edge_async(s -> Boolean.TRUE.equals(s.value("validationPassed", false))
                        ? "valid" : "invalid"), Map.of("valid", "review", "invalid", "reject"))
                .addConditionalEdges("review", edge_async(s -> Boolean.TRUE.equals(s.value("approved", false))
                        ? "accept" : "deny"), Map.of("accept", "book", "deny", "reject"))
                .addEdge("reject", END)
                .addEdge("book", END);
    }

    private Map<String, Object> extract(OverAllState state) {
        String receipt = state.<String>value("receipt").orElse("");
        if (receipt.length() > 4000) {
            throw new IllegalArgumentException("receipt 不能超过 4000 字");
        }
        String amount = uniqueField("金额", AMOUNT, receipt);
        long cents = 0;
        boolean amountValid = false;
        try {
            cents = new BigDecimal(amount).movePointRight(2).longValueExact();
            amountValid = cents > 0;
        } catch (NumberFormatException | ArithmeticException ignored) {
            // 格式、精度、溢出错误均变成失败状态；不把解析失败变成默认可报销金额。
        }
        Map<String, Object> result = new HashMap<>();
        result.put("requestId", state.<String>value("requestId").orElse("").trim());
        result.put("amountCents", cents);
        result.put("amountValid", amountValid);
        result.put("category", uniqueField("类别", CATEGORY, receipt));
        result.put("invoiceId", uniqueField("发票号", INVOICE, receipt));
        result.put("extractionMode", "regex-not-ocr-not-llm");
        result.put("approved", false);
        result.put("posted", false);
        result.put("voucherId", "");
        result.put("rejectionReason", "");
        result.put("expenseStatus", "validating");
        result.put("trace", List.of("extract"));
        return result;
    }

    private Map<String, Object> validateReceipt(OverAllState state) {
        boolean valid = Boolean.TRUE.equals(state.value("amountValid", false))
                && LIMITS.containsKey(state.value("category", ""))
                && !state.value("invoiceId", "").isBlank()
                && state.value("requestId", "").matches("[A-Za-z0-9_-]{1,64}");
        return Map.of("receiptValid", valid,
                "receiptReason", valid ? "" : "单据无效：检查正金额、唯一字段、支持类别、发票号和 requestId",
                "trace", List.of("validate_receipt"));
    }

    private Map<String, Object> validateLimit(OverAllState state) {
        long limit = LIMITS.getOrDefault(state.value("category", ""), 0L);
        long cents = state.<Number>value("amountCents").orElseThrow().longValue();
        boolean valid = Boolean.TRUE.equals(state.value("amountValid", false)) && limit > 0 && cents <= limit;
        return Map.of("limitValid", valid, "limitCents", limit,
                "limitReason", valid ? "" : "金额无效、类别不支持或超过类别限额", "trace", List.of("validate_limit"));
    }

    private Map<String, Object> aggregate(OverAllState state) {
        // 两个分支只返回各自增量，fan-in 完成后才在这里合并决策。
        boolean passed = Boolean.TRUE.equals(state.value("receiptValid", false))
                && Boolean.TRUE.equals(state.value("limitValid", false));
        String reason = java.util.stream.Stream.of(state.value("receiptReason", ""), state.value("limitReason", ""))
                .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining("；"));
        return Map.of("validationPassed", passed, "rejectionReason", reason,
                "expenseStatus", passed ? "awaiting_review" : "rejected", "trace", List.of("aggregate"));
    }

    private Map<String, Object> reject(OverAllState state) {
        String reason = state.value("rejectionReason", "");
        return Map.of("expenseStatus", "rejected", "posted", false, "voucherId", "",
                "rejectionReason", reason.isBlank() ? "人工审核拒绝" : reason, "trace", List.of("reject"));
    }

    private Map<String, Object> book(OverAllState state) {
        if (!Boolean.TRUE.equals(state.value("approved", false))
                || !Boolean.TRUE.equals(state.value("validationPassed", false))) {
            throw new IllegalStateException("校验和人工审批都通过后才能模拟入账");
        }
        String requestId = state.<String>value("requestId").orElseThrow();
        long cents = state.<Number>value("amountCents").orElseThrow().longValue();
        // 检查与首次创建处于同一个原子 compute 中；重复请求不能覆盖原始金额或凭证。
        Booking booking = ledger.compute(requestId, (key, existing) -> existing != null ? existing
                : new Booking(cents, "SIM-VOUCHER-" + UUID.randomUUID()));
        if (booking.amountCents() != cents) {
            return Map.of("expenseStatus", "conflict", "posted", false, "voucherId", "",
                    "rejectionReason", "requestId 已用于不同金额，原台账未改变", "trace", List.of("book"));
        }
        return Map.of("expenseStatus", "posted", "posted", true, "voucherId", booking.voucherId(),
                "trace", List.of("book"));
    }

    private static Pattern fieldPattern(String name, String valuePattern) {
        return Pattern.compile("(?:^|[;；\\n])\\s*" + name + "\\s*[:：=]\\s*" + valuePattern
                + "\\s*(?=[;；\\n]|$)");
    }

    private static String uniqueField(String name, Pattern pattern, String receipt) {
        // 先统计字段声明，而非仅统计合法值："金额=abc;金额=20" 也必须拒绝。
        Pattern declaration = Pattern.compile("(?:^|[;；\\n])\\s*" + Pattern.quote(name) + "\\s*[:：=]");
        if (declaration.matcher(receipt).results().count() != 1) {
            return "";
        }
        var matches = pattern.matcher(receipt);
        return matches.find() ? matches.group(1).trim() : "";
    }

    private record Booking(long amountCents, String voucherId) { }
}
