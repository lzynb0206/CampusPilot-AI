package com.example.demo.service.poster;

import com.example.demo.agent.campus.CampusGoalParser;
import com.example.demo.agent.campus.CampusPosterPromptBuilder;
import com.example.demo.agent.campus.CampusPosterSpec;
import com.example.demo.service.ai.AlibabaAiService;
import org.springframework.stereotype.Service;

@Service
public class CampusPosterService {
    private final AlibabaAiService aiService;
    private final CampusPosterRenderer renderer;
    private final CampusPosterPromptBuilder promptBuilder;
    private final CampusGoalParser goalParser;

    public CampusPosterService(
            AlibabaAiService aiService,
            CampusPosterRenderer renderer,
            CampusPosterPromptBuilder promptBuilder,
            CampusGoalParser goalParser) {
        this.aiService = aiService;
        this.renderer = renderer;
        this.promptBuilder = promptBuilder;
        this.goalParser = goalParser;
    }

    public byte[] generate(CampusPosterSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("当前没有可生成海报的活动信息");
        }
        byte[] background = aiService.generatePosterBackground(spec.backgroundPrompt());
        return renderer.render(background, spec);
    }

    public byte[] generateFromGoal(String goalDescription) {
        CampusPosterSpec spec = promptBuilder.build(goalParser.parse(goalDescription));
        if (spec == null) {
            throw new IllegalStateException("校园活动海报功能当前未启用");
        }
        return generate(spec);
    }
}
