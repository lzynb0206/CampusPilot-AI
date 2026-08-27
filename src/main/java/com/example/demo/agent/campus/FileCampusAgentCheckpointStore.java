package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class FileCampusAgentCheckpointStore implements CampusAgentCheckpointStore {
    private static final Pattern VALID_RUN_ID = Pattern.compile("^[a-f0-9]{16}$");
    private final Path checkpointDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public FileCampusAgentCheckpointStore(
            @Value("${agent.campus.checkpoint-dir:data/campus-agent-checkpoints}")
            String checkpointDirectory) {
        this(Path.of(checkpointDirectory));
    }

    FileCampusAgentCheckpointStore(Path checkpointDirectory) {
        this.checkpointDirectory = checkpointDirectory.toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<CampusAgentCheckpoint> load(String runId) {
        Path file = checkpointFile(runId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            int version = root.path("version").asInt();
            String storedRunId = root.path("run_id").asText();
            if (!runId.equals(storedRunId)) {
                throw new IllegalStateException("Agent检查点任务编号不匹配");
            }
            List<CampusTaskExecution> executions = new ArrayList<>();
            for (JsonNode node : root.path("task_executions")) {
                JsonNode outputNode = node.get("output");
                JsonNode output = outputNode == null || outputNode.isNull()
                        ? null : outputNode.deepCopy();
                executions.add(new CampusTaskExecution(
                        node.path("task_id").asText(),
                        CampusTaskStatus.valueOf(node.path("status").asText()),
                        node.path("attempts").asInt(),
                        output,
                        nullableText(node.get("error"))));
            }
            return Optional.of(new CampusAgentCheckpoint(
                    version,
                    storedRunId,
                    root.path("raw_goal").asText(),
                    executions,
                    root.path("revision_count").asInt()));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取Agent检查点：" + runId, exception);
        }
    }

    @Override
    public synchronized void save(CampusAgentCheckpoint checkpoint) {
        Path target = checkpointFile(checkpoint.runId());
        Path temporary = null;
        try {
            Files.createDirectories(checkpointDirectory);
            temporary = Files.createTempFile(
                    checkpointDirectory, checkpoint.runId() + "-", ".tmp");
            Files.writeString(
                    temporary,
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(toJson(checkpoint)),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存Agent检查点：" + checkpoint.runId(), exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // 最终目标文件已经原子替换；临时文件清理由下次启动处理。
                }
            }
        }
    }

    @Override
    public synchronized void delete(String runId) {
        try {
            Files.deleteIfExists(checkpointFile(runId));
        } catch (Exception exception) {
            throw new IllegalStateException("无法删除Agent检查点：" + runId, exception);
        }
    }

    private ObjectNode toJson(CampusAgentCheckpoint checkpoint) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", checkpoint.version());
        root.put("run_id", checkpoint.runId());
        root.put("raw_goal", checkpoint.rawGoal());
        root.put("revision_count", checkpoint.revisionCount());
        root.put("updated_at", Instant.now().toString());
        ArrayNode executions = root.putArray("task_executions");
        for (CampusTaskExecution execution : checkpoint.taskExecutions()) {
            ObjectNode value = executions.addObject();
            value.put("task_id", execution.taskId());
            value.put("status", execution.status().name());
            value.put("attempts", execution.attempts());
            if (execution.output() == null) {
                value.putNull("output");
            } else {
                value.set("output", execution.output());
            }
            if (execution.error() == null) {
                value.putNull("error");
            } else {
                value.put("error", execution.error());
            }
        }
        return root;
    }

    private Path checkpointFile(String runId) {
        validateRunId(runId);
        return checkpointDirectory.resolve(runId + ".json");
    }

    private void validateRunId(String runId) {
        if (runId == null || !VALID_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("Agent任务编号格式无效");
        }
    }

    private String nullableText(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank()
                ? null : node.asText();
    }
}
