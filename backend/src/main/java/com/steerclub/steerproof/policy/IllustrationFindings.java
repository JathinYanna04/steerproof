package com.steerclub.steerproof.policy;

import java.util.Map;

/**
 * Result of the batched ILLUSTRATION_STATES_FACT check.
 * factualTexts maps "<ASSET_TYPE>/<location>" (e.g. "CAROUSEL/slide:3") to the checker's reason.
 * checkError is non-null when the check could not run; illustrations then fail closed.
 */
public record IllustrationFindings(Map<String, String> factualTexts, String checkError, int llmCalls, int cacheHits) {

    public static IllustrationFindings none() {
        return new IllustrationFindings(Map.of(), null, 0, 0);
    }

    public static IllustrationFindings failed(String error) {
        return new IllustrationFindings(Map.of(), error == null ? "unknown error" : error, 0, 0);
    }
}
