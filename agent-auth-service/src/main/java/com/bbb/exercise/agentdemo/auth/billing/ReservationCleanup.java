package com.bbb.exercise.agentdemo.auth.billing;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** 计费预留清理任务，定时释放已经过期且尚未派发的资金占用。 */
@Component
public class ReservationCleanup {
    private final BillingService billing;

    public ReservationCleanup(BillingService billing) {
        this.billing = billing;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void cleanup() {
        billing.expireReservations();
    }
}
