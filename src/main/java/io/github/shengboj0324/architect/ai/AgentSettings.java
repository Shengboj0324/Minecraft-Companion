package io.github.shengboj0324.architect.ai;

import java.util.Set;

/** Everything the agent loop needs from config.yml, as plain values so the loop runs without a server too. */
public record AgentSettings(
        String apiKey,
        String baseUrl,
        String model,
        String effort,
        long maxTokens,
        int maxTurns,
        int minScore,
        int maxGateRejections,
        boolean vision,
        boolean fallbacks,
        int maxWidth,
        int maxHeight,
        int maxDepth,
        int maxBlocks,
        long compactAtInputTokens,
        Set<String> bannedBlocks,
        boolean webSearch) {

    public static AgentSettings defaults(String apiKey) {
        return new AgentSettings(apiKey, "", "claude-opus-5-5", "high", 32000, 40, 80, 2, true, true,
                512, 320, 512, 3_000_000, 150_000,
                Set.of("command_block", "chain_command_block", "repeating_command_block", "structure_block", "structure_void",
                        "jigsaw", "barrier", "light", "tnt", "spawner", "trial_spawner", "end_portal", "end_portal_frame",
                        "nether_portal", "end_gateway", "bedrock", "lava", "fire", "soul_fire", "moving_piston", "test_block",
                        "test_instance_block", "respawn_anchor"), false);
    }
}
