package com.etolv.neo4jtool.web;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live feed of the pages: one JSON for a page that asks now and then, and a stream of the same JSON that the server
 * pushes every few seconds (the "Live refresh" setting). A page uses the stream and falls back to asking when the
 * connection cannot carry one.
 */
@Controller
public class LiveController {

    private static final int MAX_STREAMS = 24;

    private final LiveService live;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "live-feed");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger streams = new AtomicInteger();

    public LiveController(LiveService live) {
        this.live = live;
        executor.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    @GetMapping(value = "/live.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    ResponseEntity<String> json(@RequestParam(required = false) Long migration, @RequestParam(defaultValue = "0") long after) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(live.json(migration, after));
    }

    @GetMapping(value = "/live/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@RequestParam(required = false) Long migration, @RequestParam(defaultValue = "0") long after,
                                      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (streams.get() >= MAX_STREAMS) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        streams.incrementAndGet();
        // A connection that was cut and comes back says in Last-Event-ID how far it had read.
        AtomicLong seen = new AtomicLong(Math.max(after, parse(lastEventId)));
        SseEmitter emitter = new SseEmitter(15 * 60_000L);
        int seconds = Math.max(1, live.snapshot(null, 0).intervalSeconds());
        ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        AtomicInteger closed = new AtomicInteger();
        Runnable close = () -> {
            if (closed.compareAndSet(0, 1)) {
                streams.decrementAndGet();
                if (task[0] != null) {
                    task[0].cancel(false);
                }
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(() -> {
            close.run();
            emitter.complete();
        });
        emitter.onError(e -> close.run());
        task[0] = executor.scheduleWithFixedDelay(() -> {
            try {
                LiveService.Live snapshot = live.snapshot(migration, seen.get());
                if (snapshot.job() != null) {
                    seen.set(Math.max(seen.get(), snapshot.job().lastLogId()));
                }
                emitter.send(SseEmitter.event().name("live").id(String.valueOf(seen.get()))
                        .data(com.etolv.neo4jtool.engine.Json.write(snapshot), MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                close.run();
            } catch (RuntimeException e) {
                close.run();
                emitter.completeWithError(e);
            }
        }, 0, seconds, TimeUnit.SECONDS);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(emitter);
    }

    private static long parse(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
