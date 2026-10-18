package com.steerclub.steerproof.common;

public enum PaperStatus {
    PARSING,
    EXTRACTING,
    VERIFYING,
    AWAITING_CLAIM_REVIEW,
    WRITING,
    RENDERING,
    AWAITING_ASSET_REVIEW,
    READY_TO_EXPORT,
    EXPORTED,
    FAILED
}
