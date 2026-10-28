package com.example.ticketing.show.controller;

import com.example.ticketing.show.dto.ShowDetail;
import com.example.ticketing.show.dto.ShowSummary;
import com.example.ticketing.show.service.ShowQueryService;
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

    private final ShowQueryService showQueryService;

    @GetMapping
    public List<ShowSummary> list() {
        return showQueryService.listShows();
    }

    @GetMapping("/{showId}")
    public ShowDetail get(@PathVariable Long showId) {
        return showQueryService.getShow(showId);
    }
}
