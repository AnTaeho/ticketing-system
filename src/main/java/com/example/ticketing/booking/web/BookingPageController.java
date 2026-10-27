package com.example.ticketing.booking.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// 화면 틀만 내려 준다. 데이터는 각 화면의 스크립트가 API로 읽는다
@Controller
public class BookingPageController {

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
}
