package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Callable;

/** Process-wide administrative cost guard; this is intentionally not the user wallet/billing path. */
public final class GalleryUsageGate {
    private final Semaphore concurrent;
    private final int perMinute;
    private final Clock clock;
    private final ArrayDeque<Long> starts=new ArrayDeque<>();
    public GalleryUsageGate(GalleryProperties properties) { this(properties,Clock.systemUTC()); }
    GalleryUsageGate(GalleryProperties properties,Clock clock) {
        if(properties.getMaxConcurrent()<1 || properties.getMaxConcurrent()>8 || properties.getMaxRequestsPerMinute()<1 || properties.getMaxRequestsPerMinute()>60)
            throw new IllegalArgumentException("Gallery concurrency must be 1..8 and requests per minute 1..60");
        this.concurrent=new Semaphore(properties.getMaxConcurrent());this.perMinute=properties.getMaxRequestsPerMinute();this.clock=clock;
    }
    public <T> T execute(Callable<T> action) throws Exception {
        if(!concurrent.tryAcquire()) throw limited();
        try {
            synchronized(starts) {
                long now=clock.millis();while(!starts.isEmpty() && starts.peekFirst()<=now-60000) starts.removeFirst();
                if(starts.size()>=perMinute) throw limited();starts.addLast(now);
            }
            return action.call();
        } finally { concurrent.release(); }
    }
    private static ResponseStatusException limited() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Prompt gallery administrator model-call limit reached; retry later"); }
}
