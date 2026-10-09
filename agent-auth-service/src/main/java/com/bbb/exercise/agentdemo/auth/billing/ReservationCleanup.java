package com.bbb.exercise.agentdemo.auth.billing;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class ReservationCleanup {
    private final BillingService billing;
    public ReservationCleanup(BillingService billing) {this.billing=billing;}
    @Scheduled(fixedDelay=60000,initialDelay=60000) public void cleanup() {billing.expireReservations();}
}
