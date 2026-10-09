package com.bbb.exercise.agentdemo.orchestrator.execution;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutionBudgetTest {
    @Test void productiveLongTaskExtendsOnlyWhenItNeedsAnotherRound() {
        var b = ExecutionBudget.initial();
        for (int n=0;n<4;n++) b=b.recordDecision(100,20,1000,"result-"+n,true,true);
        assertThat(b.roundLimit()).isEqualTo(4);
        var next=b.admit();
        assertThat(next.reason()).isEqualTo("READY");
        assertThat(next.budget().roundLimit()).isEqualTo(8);
        assertThat(next.budget().tokensUsed()).isEqualTo(480);
    }
    @Test void repeatedDecisionWithoutProgressStopsInsteadOfSpendingMore() {
        var b=ExecutionBudget.initial().recordDecision(10,10,1,"same",false,true)
                .recordDecision(10,10,1,"same",false,true);
        assertThat(b.admit().reason()).isEqualTo("NEEDS_INPUT");
    }
    @Test void resumingDoesNotEraseConsumedTokenBudget() {
        var b=new ExecutionBudget(7,8,5,64000,1200,"x",0,true);
        var resumed=b.extend(4);
        assertThat(resumed.tokensUsed()).isEqualTo(64000);
        assertThat(resumed.admit().reason()).isEqualTo("WAITING_FOR_BUDGET");
    }
    @Test void hardRoundAndToolLimitsRemainEvenAfterExtension() {
        assertThat(new ExecutionBudget(64,64,1,10,10,"x",0,true).extend(4).admit().reason())
                .isEqualTo("WAITING_FOR_BUDGET");
        assertThat(new ExecutionBudget(1,4,128,10,10,"x",0,true).admit().reason())
                .isEqualTo("WAITING_FOR_BUDGET");
    }
    @Test void shortTaskDoesNotIncreaseRoundLimit() {
        var b=ExecutionBudget.initial().recordDecision(4,8,10,"final",true,false);
        assertThat(b.roundLimit()).isEqualTo(4);
        assertThat(b.roundsUsed()).isEqualTo(1);
        assertThat(b.toolCalls()).isZero();
    }
}
