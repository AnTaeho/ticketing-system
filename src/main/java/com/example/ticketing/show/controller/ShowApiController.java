package com.example.ticketing.show.controller;

import com.example.ticketing.show.dto.ShowDetailResponse;
import com.example.ticketing.show.dto.ShowSummaryResponse;
import com.example.ticketing.show.service.ShowReader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shows")
@RequiredArgsConstructor
public class ShowApiController {

    private final ShowReader showReader;

    @GetMapping
    public List<ShowSummaryResponse> list() {
        return showReader.listShows().stream().map(ShowSummaryResponse::from).toList();
    }

    @GetMapping("/{showId}")
    public ShowDetailResponse get(@PathVariable Long showId) {
        return ShowDetailResponse.from(showReader.getShow(showId));
    }
}
