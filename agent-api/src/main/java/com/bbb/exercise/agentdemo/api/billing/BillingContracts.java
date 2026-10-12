package com.bbb.exercise.agentdemo.api.billing;

/** Amounts are integer micro-yuan, never floating point. Token counts are nullable if unavailable. */
public final class BillingContracts {
    private BillingContracts() {}

    /** 单次模型调用的费用预留请求，绑定幂等请求号、能力和模型。 */
    public record Reserve(String requestId, String capability, String model) {}

    /** 费用预留结果，返回试用标记、冻结金额及当前状态。 */
    public record Reservation(
            String requestId, boolean trial, long reservedMicros, String status) {}

    /** 供应商调用的结算凭据，携带实测 Token 用量和供应商请求号。 */
    public record Settlement(Long inputTokens, Long outputTokens, String providerRequestId) {}

    /** 模型调用组的准入请求，以参数摘要约束幂等重放并区分前后台任务。 */
    public record GroupReserve(
            String callId, String parameterHash, String capability, boolean foreground,
            Integer expectedGeneration) {
        public GroupReserve(String callId, String parameterHash, String capability, boolean foreground) {
            this(callId, parameterHash, capability, foreground, null);
        }
    }

    /** 模型调用组的持久化快照，记录获胜结果、代次和已知及未知用量。 */
    public record GroupState(
            String callId,
            String status,
            String receipt,
            String winningRole,
            int generation,
            long inputTokens,
            long outputTokens,
            long unknownTokens) {
        public GroupState(String callId, String status, String receipt, String winningRole) {
            this(callId, status, receipt, winningRole, 1, 0, 0, 0);
        }

        public GroupState(
                String callId, String status, String receipt, String winningRole, int generation) {
            this(callId, status, receipt, winningRole, generation, 0, 0, 0);
        }
    }

    /** 调用组内单次尝试的预留参数，固定供应商配置和计划 Token 上限。 */
    public record AttemptReserve(
            String role,
            String provider,
            String model,
            String endpoint,
            String credentialId,
            long plannedTokenUpper) {
        public AttemptReserve(
                String role, String provider, String model, String endpoint, String credentialId) {
            this(role, provider, model, endpoint, credentialId, 0);
        }
    }

    /** 单次供应商尝试的状态快照，用代次区分重试前后的执行。 */
    public record AttemptState(String role, String status, int generation) {
        public AttemptState(String role, String status) {
            this(role, status, 1);
        }
    }

    /** 状态变更请求携带的尝试代次，用于拒绝过期执行者的操作。 */
    public record AttemptAction(int generation) {}

    /** 单次尝试的结果回执，关联执行代次、生成内容和供应商实测用量。 */
    public record AttemptReceipt(
            String role,
            String resultJson,
            Long inputTokens,
            Long outputTokens,
            String providerRequestId,
            int generation) {
        public AttemptReceipt(
                String role,
                String resultJson,
                Long inputTokens,
                Long outputTokens,
                String providerRequestId) {
            this(role, resultJson, inputTokens, outputTokens, providerRequestId, 1);
        }
    }

    /** 钱包查询结果，汇总余额、冻结资金、免费额度及调用阻断状态。 */
    public record Wallet(
            long balanceMicros,
            long reservedMicros,
            int freeImages,
            boolean arrears,
            boolean lowBalance,
            boolean blocked,
            String activeUsageId,
            long freeTokens) {
        public Wallet(
                long balanceMicros,
                long reservedMicros,
                int freeImages,
                boolean arrears,
                boolean lowBalance,
                boolean blocked,
                String activeUsageId) {
            this(
                    balanceMicros,
                    reservedMicros,
                    freeImages,
                    arrears,
                    lowBalance,
                    blocked,
                    activeUsageId,
                    0);
        }
    }

    /** 模型调用的计费流水视图，分别展示实测 Token、抵扣免费额度、收费和供应商请求号。 */
    public record Usage(
            String id,
            String capability,
            String model,
            String status,
            Long inputTokens,
            Long outputTokens,
            String metering,
            long chargedMicros,
            boolean trial,
            String providerRequestId,
            java.time.LocalDateTime createdAt,
            long freeTokensUsed) {
        public Usage(
                String id,
                String capability,
                String model,
                String status,
                Long inputTokens,
                Long outputTokens,
                String metering,
                long chargedMicros,
                boolean trial,
                String providerRequestId,
                java.time.LocalDateTime createdAt) {
            this(
                    id,
                    capability,
                    model,
                    status,
                    inputTokens,
                    outputTokens,
                    metering,
                    chargedMicros,
                    trial,
                    providerRequestId,
                    createdAt,
                    0);
        }
    }
}
