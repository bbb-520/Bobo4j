package com.bbb.exercise.agentdemo.orchestrator.execution;

/** Durable consumption: resuming a run never resets its hard safety limits. */
public record ExecutionBudget(int roundsUsed, int roundLimit, int toolCalls, long tokensUsed,
                              long activeMillis, String lastFingerprint, int repeated,
                              boolean lastProgress,long reservedTokens) {
    public ExecutionBudget(int roundsUsed,int roundLimit,int toolCalls,long tokensUsed,long activeMillis,String lastFingerprint,int repeated,boolean lastProgress){this(roundsUsed,roundLimit,toolCalls,tokensUsed,activeMillis,lastFingerprint,repeated,lastProgress,0);}
    public static ExecutionBudget initial() { return new ExecutionBudget(0,4,0,0,0,"",0,true); }
    public Admission admit() {
        if (roundsUsed>=64 || toolCalls>=128 || tokensUsed+reservedTokens>=64_000 || activeMillis>=1_800_000)
            return new Admission(this,"WAITING_FOR_BUDGET");
        if (repeated>=2) return new Admission(this,"NEEDS_INPUT");
        if (roundsUsed>=roundLimit) {
            if (!lastProgress) return new Admission(this,"NEEDS_INPUT");
            return new Admission(extend(4),"READY");
        }
        return new Admission(this,"READY");
    }
    public ExecutionBudget recordDecision(long input, long output, long elapsed, String fingerprint,
                                          boolean progress, boolean tool) {
        if (input<0 || output<0 || elapsed<0) throw new IllegalArgumentException("预算用量不能为负数");
        int same = progress ? 0 : (fingerprint.equals(lastFingerprint) ? repeated+1 : 1);
        return new ExecutionBudget(roundsUsed+1,roundLimit,toolCalls+(tool?1:0),
                Math.addExact(tokensUsed,Math.addExact(input,output)),Math.addExact(activeMillis,elapsed),
                fingerprint,same,progress,reservedTokens);
    }
    public ExecutionBudget charge(long input,long output,long elapsed) {
        if(input<0||output<0||elapsed<0)throw new IllegalArgumentException("预算用量不能为负数");
        return new ExecutionBudget(roundsUsed,roundLimit,toolCalls,Math.addExact(tokensUsed,Math.addExact(input,output)),
                Math.addExact(activeMillis,elapsed),lastFingerprint,repeated,lastProgress,reservedTokens);
    }
    public ExecutionBudget extend(int rounds) {
        if(rounds<0||rounds>64)throw new IllegalArgumentException("追加轮次无效");
        return new ExecutionBudget(roundsUsed,Math.min(64,roundLimit+rounds),toolCalls,tokensUsed,
                activeMillis,lastFingerprint,repeated,lastProgress,reservedTokens);
    }
    public long availableTokens(){return Math.max(0,64_000-tokensUsed-reservedTokens);}
    public ExecutionBudget reserve(long amount){if(amount<0||amount>availableTokens())throw new IllegalArgumentException("剩余token预算不足");return withReserved(Math.addExact(reservedTokens,amount));}
    public ExecutionBudget withReserved(long value){if(value<0)throw new IllegalArgumentException("token预留不能为负数");return new ExecutionBudget(roundsUsed,roundLimit,toolCalls,tokensUsed,activeMillis,lastFingerprint,repeated,lastProgress,value);}
    public record Admission(ExecutionBudget budget,String reason) {}
}
