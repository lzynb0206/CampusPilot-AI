package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;

public interface CampusTaskRunner {
    JsonNode execute(AgentTask task, CampusExecutionContext context);
}

