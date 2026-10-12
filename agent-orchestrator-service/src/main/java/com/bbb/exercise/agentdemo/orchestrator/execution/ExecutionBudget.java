package com.bbb.exercise.agentdemo.orchestrator.execution;

/** Durable consumption: resuming a run never resets its hard safety limits. */
public record ExecutionBudget(
        int roundsUsed,
        int roundLimit,
        int toolCalls,
        long tokensUsed,
        long activeMillis,
        String lastFingerprint,
        int repeated,
        boolean lastProgress,
        long reservedTokens) {
    public ExecutionBudget(
            int roundsUsed,
            int roundLimit,
            int toolCalls,
            long tokensUsed,
            long activeMillis,
            String lastFingerprint,
            int repeated,
            boolean lastProgress) {
        this(
                roundsUsed,
                roundLimit,
                toolCalls,
                tokensUsed,
                activeMillis,
                lastFingerprint,
                repeated,
                lastProgress,
                0);
    }

    // 1. 初始预算：为任务建立轮次窗口和各类资源上限。
    public static ExecutionBudget initial() {
        return new ExecutionBudget(0, 4, 0, 0, 0, "", 0, true);
    }

    // 2. 轮次准入：综合已用资源、未知预留和进展判断是否允许继续。
    public Admission admit() {
        if (roundsUsed >= 64
                || toolCalls >= 128
                || tokensUsed + reservedTokens >= 64_000
                || activeMillis >= 1_800_000) {
            return new Admission(this, "WAITING_FOR_BUDGET");
        }

        if (repeated >= 2) {
            return new Admission(this, "NEEDS_INPUT");
        }

        if (roundsUsed >= roundLimit) {
            if (!lastProgress) {
                return new Admission(this, "NEEDS_INPUT");
            }

            return new Admission(extend(4), "READY");
        }

        return new Admission(this, "READY");
    }

    // 2.1. 进展记录：记录工具与决策效果，供后续自动扩展轮次窗口判断。
    public ExecutionBudget recordDecision(
            long input,
            long output,
            long elapsed,
            String fingerprint,
            boolean progress,
            boolean tool) {
        if (input < 0 || output < 0 || elapsed < 0) {
            throw new IllegalArgumentException("预算用量不能为负数");
        }

        int same = progress ? 0 : (fingerprint.equals(lastFingerprint) ? repeated + 1 : 1);

        return new ExecutionBudget(
                roundsUsed + 1,
                roundLimit,
                toolCalls + (tool ? 1 : 0),
                Math.addExact(tokensUsed, Math.addExact(input, output)),
                Math.addExact(activeMillis, elapsed),
                fingerprint,
                same,
                progress,
                reservedTokens);
    }

    // 3. 实际用量：将调用消耗计入预算，不因任务暂停或继续而清零。
    public ExecutionBudget charge(long input, long output, long elapsed) {
        if (input < 0 || output < 0 || elapsed < 0) {
            throw new IllegalArgumentException("预算用量不能为负数");
        }

        return new ExecutionBudget(
                roundsUsed,
                roundLimit,
                toolCalls,
                Math.addExact(tokensUsed, Math.addExact(input, output)),
                Math.addExact(activeMillis, elapsed),
                lastFingerprint,
                repeated,
                lastProgress,
                reservedTokens);
    }

    public ExecutionBudget extend(int rounds) {
        if (rounds < 0 || rounds > 64) {
            throw new IllegalArgumentException("追加轮次无效");
        }

        return new ExecutionBudget(
                roundsUsed,
                Math.min(64, roundLimit + rounds),
                toolCalls,
                tokensUsed,
                activeMillis,
                lastFingerprint,
                repeated,
                lastProgress,
                reservedTokens);
    }

    public long availableTokens() {
        return Math.max(0, 64_000 - tokensUsed - reservedTokens);
    }

    // 3.1. Token 预留：派发前占用额度，防止并发步骤突破任务上限。
    public ExecutionBudget reserve(long amount) {
        if (amount < 0 || amount > availableTokens()) {
            throw new IllegalArgumentException("剩余token预算不足");
        }

        return withReserved(Math.addExact(reservedTokens, amount));
    }

    public ExecutionBudget withReserved(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("token预留不能为负数");
        }

        return new ExecutionBudget(
                roundsUsed,
                roundLimit,
                toolCalls,
                tokensUsed,
                activeMillis,
                lastFingerprint,
                repeated,
                lastProgress,
                value);
    }

    /** 执行预算的准入判断，返回可用预算及拒绝或停止原因。 */
    public record Admission(ExecutionBudget budget, String reason) {}
}
