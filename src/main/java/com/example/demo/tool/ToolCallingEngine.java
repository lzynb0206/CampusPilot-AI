package com.example.demo.tool;

import com.example.demo.config.ConcurrencyConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.function.Function;

@Slf4j
@Component
public class ToolCallingEngine {
    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int MAX_TOOL_CALLS_PER_ROUND = 8;
    private static final int MAX_TOOL_RESULT_CHARACTERS = 8_000;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ExecutorService taskExecutor;

    public ToolCallingEngine(ToolRegistry toolRegistry) {
        this(toolRegistry, ForkJoinPool.commonPool());
    }

    @Autowired
    public ToolCallingEngine(
            ToolRegistry toolRegistry,
            @Qualifier(ConcurrencyConfig.APPLICATION_TASK_EXECUTOR)
            ExecutorService taskExecutor) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = new ObjectMapper();
        this.taskExecutor = taskExecutor;
    }

    public List<Map<String, Object>> toolDefinitions() {
        return toolRegistry.definitions();
    }

    public String run(
            List<Map<String, Object>> initialMessages,
            Function<List<Map<String, Object>>, JsonNode> modelCall) {
        List<Map<String, Object>> messages = new ArrayList<>(initialMessages);
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode message = modelCall.apply(List.copyOf(messages));
            if (message == null || message.isMissingNode() || !message.isObject()) {
                throw new IllegalStateException("模型未返回有效消息");
            }

            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = message.path("content").asText();
                if (!StringUtils.hasText(content)) {
                    throw new IllegalStateException("模型未返回最终回答");
                }
                return content.trim();
            }

            messages.add(toMap(message));
            messages.addAll(executeToolCalls(toolCalls));
        }
        throw new IllegalStateException("工具调用轮数超过限制：" + MAX_TOOL_ROUNDS);
    }

    private List<Map<String, Object>> executeToolCalls(JsonNode toolCalls) {
        if (toolCalls.size() > MAX_TOOL_CALLS_PER_ROUND) {
            throw new IllegalStateException(
                    "单轮工具调用数量超过限制：" + MAX_TOOL_CALLS_PER_ROUND);
        }
        if (toolCalls.size() == 1) {
            return List.of(executeToolCall(toolCalls.get(0)));
        }

        log.info("开始并行执行工具 count={}", toolCalls.size());
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (JsonNode toolCall : toolCalls) {
                futures.add(taskExecutor.submit(() -> executeToolCall(toolCall)));
            }

            List<Map<String, Object>> results = new ArrayList<>(futures.size());
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并行工具执行被中断", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("并行工具执行失败", exception.getCause());
        }
    }

    private Map<String, Object> executeToolCall(JsonNode toolCall) {
        String toolCallId = toolCall.path("id").asText();
        String toolName = toolCall.at("/function/name").asText();
        JsonNode argumentsNode = toolCall.at("/function/arguments");
        if (!StringUtils.hasText(toolCallId) || !StringUtils.hasText(toolName)
                || argumentsNode.isMissingNode()) {
            throw new IllegalStateException("模型返回的工具调用缺少 id、名称或参数");
        }
        String arguments = argumentsNode.isTextual()
                ? argumentsNode.asText()
                : argumentsNode.toString();
        String result;
        try {
            result = compactToolResult(toolRegistry.execute(toolName, arguments));
            log.info("工具执行成功 tool={}", toolName);
        } catch (Exception exception) {
            result = errorResult(exception);
            log.warn("工具执行失败 tool={}", toolName, exception);
        }
        return Map.of(
                "role", "tool",
                "tool_call_id", toolCallId,
                "content", result
        );
    }

    private Map<String, Object> toMap(JsonNode node) {
        try {
            return objectMapper.readValue(
                    node.toString(), new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存工具调用消息", exception);
        }
    }

    private String errorResult(Exception exception) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", exception.getMessage() == null ? "工具执行失败" : exception.getMessage()
            ));
        } catch (Exception ignored) {
            return "{\"success\":false,\"error\":\"工具执行失败\"}";
        }
    }

    private String compactToolResult(String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_CHARACTERS) {
            return result;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "truncated", true,
                    "original_characters", result.length(),
                    "content_preview", result.substring(0, MAX_TOOL_RESULT_CHARACTERS),
                    "notice", "工具结果过长，已截断以控制上下文Token"
            ));
        } catch (Exception exception) {
            return result.substring(0, MAX_TOOL_RESULT_CHARACTERS);
        }
    }
}
