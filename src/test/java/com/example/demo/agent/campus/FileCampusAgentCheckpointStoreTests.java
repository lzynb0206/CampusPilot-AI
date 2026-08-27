package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCampusAgentCheckpointStoreTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsAndDeletesCheckpoint() {
        FileCampusAgentCheckpointStore store =
                new FileCampusAgentCheckpointStore(temporaryDirectory);
        var output = JsonNodeFactory.instance.objectNode().put("value", 42);
        CampusAgentCheckpoint checkpoint = new CampusAgentCheckpoint(
                1,
                "0123456789abcdef",
                "帮我策划校园活动",
                List.of(new CampusTaskExecution(
                        "resolve_constraints",
                        CampusTaskStatus.SUCCEEDED,
                        1,
                        output,
                        null)),
                1);

        store.save(checkpoint);
        CampusAgentCheckpoint loaded = store.load(checkpoint.runId()).orElseThrow();

        assertEquals(checkpoint.runId(), loaded.runId());
        assertEquals(checkpoint.rawGoal(), loaded.rawGoal());
        assertEquals(1, loaded.revisionCount());
        assertEquals(42, loaded.taskExecutions().get(0).output().path("value").asInt());

        store.delete(checkpoint.runId());
        assertFalse(store.load(checkpoint.runId()).isPresent());
    }

    @Test
    void rejectsRunIdThatCouldEscapeCheckpointDirectory() {
        FileCampusAgentCheckpointStore store =
                new FileCampusAgentCheckpointStore(temporaryDirectory);

        assertThrows(IllegalArgumentException.class, () -> store.load("../../outside"));
        assertTrue(temporaryDirectory.toFile().isDirectory());
    }
}
