package com.bbb.exercise.agentdemo.auth.billing;

import java.math.BigInteger;

/** Shared token arithmetic for legacy calls and durable model groups. */
final class TokenAllowance {
    private TokenAllowance() {}
    static long total(long input, long output) {
        try { return Math.addExact(input, output); }
        catch (ArithmeticException e) { throw new BillingException(400, "用量超出计费范围"); }
    }
    static long cashHold(long grossMicros, long freeTokens, long rate) {
        // Floor the credit value so rounding never under-authorizes a paid remainder.
        return BigInteger.valueOf(grossMicros).subtract(BigInteger.valueOf(freeTokens)
                .multiply(BigInteger.valueOf(rate)).divide(BigInteger.valueOf(1000)))
                .max(BigInteger.ZERO).longValueExact();
    }
}
