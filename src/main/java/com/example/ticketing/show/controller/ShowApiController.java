package com.example.ticketing.show.controller;

import com.example.ticketing.show.dto.ShowDetailResult;
import com.example.ticketing.show.dto.ShowSummaryResult;
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
    public List<ShowSummaryResult> list() {
        return showReader.listShows();
    }

    @GetMapping("/{showId}")
    public ShowDetailResult get(@PathVariable Long showId) {
        return showReader.getShow(showId);
    }
}
