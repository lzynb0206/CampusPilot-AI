package com.example.demo.agent.campus;

import java.util.Optional;

public interface CampusAgentCheckpointStore {
    Optional<CampusAgentCheckpoint> load(String runId);

    void save(CampusAgentCheckpoint checkpoint);

    void delete(String runId);

    static CampusAgentCheckpointStore noop() {
        return new CampusAgentCheckpointStore() {
            @Override
            public Optional<CampusAgentCheckpoint> load(String runId) {
                return Optional.empty();
            }

            @Override
            public void save(CampusAgentCheckpoint checkpoint) {
            }

            @Override
            public void delete(String runId) {
            }
        };
    }
}
