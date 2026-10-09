package com.bbb.exercise.agentdemo.api.billing;

/** Amounts are integer micro-yuan, never floating point. Token counts are nullable if unavailable. */
public final class BillingContracts {
    private BillingContracts() {}
    public record Reserve(String requestId, String capability, String model) {}
    public record Reservation(String requestId, boolean trial, long reservedMicros, String status) {}
    public record Settlement(Long inputTokens, Long outputTokens, String providerRequestId) {}
    public record GroupReserve(String callId, String parameterHash, String capability, boolean foreground) {}
    public record GroupState(String callId, String status, String receipt, String winningRole,int generation,long inputTokens,long outputTokens,long unknownTokens) {
        public GroupState(String callId,String status,String receipt,String winningRole){this(callId,status,receipt,winningRole,1,0,0,0);}
        public GroupState(String callId,String status,String receipt,String winningRole,int generation){this(callId,status,receipt,winningRole,generation,0,0,0);}
    }
    public record AttemptReserve(String role, String provider, String model, String endpoint, String credentialId,long plannedTokenUpper) {public AttemptReserve(String role,String provider,String model,String endpoint,String credentialId){this(role,provider,model,endpoint,credentialId,0);}}
    public record AttemptState(String role, String status,int generation) {public AttemptState(String role,String status){this(role,status,1);}}
    public record AttemptAction(int generation) {}
    public record AttemptReceipt(String role, String resultJson, Long inputTokens, Long outputTokens, String providerRequestId,int generation) {
        public AttemptReceipt(String role,String resultJson,Long inputTokens,Long outputTokens,String providerRequestId){this(role,resultJson,inputTokens,outputTokens,providerRequestId,1);}
    }
    public record Wallet(long balanceMicros, long reservedMicros, int freeImages, boolean arrears,
                         boolean lowBalance, boolean blocked, String activeUsageId, long freeTokens) {
        public Wallet(long balanceMicros, long reservedMicros, int freeImages, boolean arrears,
                      boolean lowBalance, boolean blocked, String activeUsageId) {
            this(balanceMicros, reservedMicros, freeImages, arrears, lowBalance, blocked, activeUsageId, 0);
        }
    }
    public record Usage(String id, String capability, String model, String status, Long inputTokens,
                        Long outputTokens, String metering, long chargedMicros, boolean trial,
                        String providerRequestId, java.time.LocalDateTime createdAt) {}
}
