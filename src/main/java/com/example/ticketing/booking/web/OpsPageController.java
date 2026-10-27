package com.example.ticketing.booking.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// 운영 현황 화면은 집계 API와 함께 prod에서 빠진다
@Profile("!prod")
@Controller
public class OpsPageController {

    @GetMapping("/booking/ops/performances/{performanceId}")
    public String ops(@PathVariable long performanceId, Model model) {
        model.addAttribute("performanceId", performanceId);
        return "booking/ops";
    }
}
