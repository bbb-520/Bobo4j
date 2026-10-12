package com.bbb.exercise.agentdemo.contentservice.zine;

/** Zine 图片供应商调用异常，保留失败说明和底层原因。 */
public class ZineProviderException extends RuntimeException {
    public ZineProviderException(String m) {
        super(m);
    }

    public ZineProviderException(String m, Throwable c) {
        super(m, c);
    }
}
