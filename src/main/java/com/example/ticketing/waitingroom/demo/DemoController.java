package com.example.ticketing.waitingroom.demo;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Controller
@RequestMapping("/waitingroom/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoService demoService;

    @GetMapping
    public String demoPage() {
        return "demo/index";
    }

    @PostMapping("/start")
    @ResponseBody
    public ResponseEntity<Void> start(
            @RequestParam Long concertId,
            @RequestParam int users) {
        demoService.start(concertId, users);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream(@RequestParam Long concertId) {
        SseEmitter emitter = new SseEmitter(600_000L);

        Thread pushThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    DemoStats stats = demoService.buildStats(concertId);
                    emitter.send(SseEmitter.event().name("stats").data(stats));

                    if (!stats.running()) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(400);
                }
            } catch (IOException ignored) {
                // 클라이언트 연결 끊김
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pushThread.setDaemon(true);
        pushThread.start();

        emitter.onCompletion(pushThread::interrupt);
        emitter.onTimeout(pushThread::interrupt);
        emitter.onError(e -> pushThread.interrupt());

        return emitter;
    }
}
