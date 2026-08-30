package com.example.demo.service.ai;

public record PosterBackgroundReview(
        boolean textDetected,
        int qualityScore,
        String reason) {

    public PosterBackgroundReview {
        qualityScore = Math.max(0, Math.min(100, qualityScore));
        reason = reason == null ? "" : reason.trim();
    }

    public boolean passes(int minimumScore) {
        return !textDetected && qualityScore >= minimumScore;
    }
}
