package com.example.ticketing.web.controller;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.web.DemoMode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// 화면 틀만 내려 준다. 데이터는 각 화면의 스크립트가 API로 읽는다
@Controller
public class PageController {

    private final TicketingProperties properties;
    // 결제 결과를 미리 고르는 시연 상자는 prod가 아닐 때만 그린다. 서버도 prod에서는 그 헤더를 무시한다
    private final boolean demoPg;
    // 좌석 화면의 생존 신호 간격. 무응답 한도 안에 세 번 가게 하고, 줄을 세우지 않으면 지킬 자리가 없어 0(보내지 않음)이다
    private final long heartbeatMs;

    public PageController(TicketingProperties properties, DemoMode demoMode) {
        this.properties = properties;
        this.demoPg = demoMode.enabled();
        this.heartbeatMs = properties.queue().required()
                ? properties.queue().processingIdleTimeout().toMillis() / 3 : 0;
    }

    @GetMapping({"/booking", "/booking/"})
    public String home() {
        return "redirect:/booking/shows";
    }

    @GetMapping("/booking/login")
    public String login() {
        return "booking/login";
    }

    @GetMapping("/booking/shows")
    public String shows() {
        return "booking/shows";
    }

    @GetMapping("/booking/shows/{showId}")
    public String show(@PathVariable long showId, Model model) {
        model.addAttribute("showId", showId);
        return "booking/show";
    }

    @GetMapping("/booking/performances/{performanceId}/queue")
    public String queue(@PathVariable long performanceId, Model model) {
        model.addAttribute("performanceId", performanceId);
        return "booking/queue";
    }

    @GetMapping("/booking/performances/{performanceId}/seats")
    public String seats(@PathVariable long performanceId, Model model) {
        model.addAttribute("performanceId", performanceId);
        model.addAttribute("maxSeats", properties.hold().maxSeats());
        model.addAttribute("heartbeatMs", heartbeatMs);
        return "booking/seats";
    }

    @GetMapping("/booking/holds/{holdId}/checkout")
    public String checkout(@PathVariable long holdId, Model model) {
        model.addAttribute("holdId", holdId);
        model.addAttribute("demoPg", demoPg);
        return "booking/checkout";
    }

    @GetMapping("/booking/bookings")
    public String bookings() {
        return "booking/bookings";
    }
}
