package com.steerclub.steerproof.common;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class IdGenerator {

    private final AtomicInteger paperSeq = new AtomicInteger(0);
    private final AtomicInteger batchSeq = new AtomicInteger(0);

    public String nextPaperId() {
        return String.format("P-%04d", paperSeq.incrementAndGet());
    }

    public String nextBatchId() {
        return String.format("B-%04d", batchSeq.incrementAndGet());
    }

    public String passageId(String paperId, int ord) {
        String num = paperId.startsWith("P-") ? paperId.substring(2) : paperId;
        return String.format("E-%s-%04d", num, ord);
    }

    public String claimId(String paperId, int claimNum) {
        String num = paperId.startsWith("P-") ? paperId.substring(2) : paperId;
        return String.format("C-%s-%03d", num, claimNum);
    }

    public String assetId(String paperId, AssetType type) {
        String num = paperId.startsWith("P-") ? paperId.substring(2) : paperId;
        String suffix = switch (type) {
            case CAROUSEL -> "CAR";
            case STATIC -> "STA";
            case REEL -> "REEL";
            case CAPTION -> "CAP";
        };
        return String.format("A-%s-%s", num, suffix);
    }

    public void initSequenceFromMax(int maxPaperId, int maxBatchId) {
        paperSeq.set(Math.max(paperSeq.get(), maxPaperId));
        batchSeq.set(Math.max(batchSeq.get(), maxBatchId));
    }
}
