package com.example.ticketing.booking.show.api;

import com.example.ticketing.booking.show.ShowAdminService;
import com.example.ticketing.booking.show.dto.PerformanceCreated;
import com.example.ticketing.booking.show.dto.ShowCreated;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!prod")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ShowAdminApiController {

    private final ShowAdminService showAdminService;

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowCreated createShow(@Valid @RequestBody CreateShowRequest request) {
        return new ShowCreated(
                showAdminService.createShow(request.title(), request.venueName(), request.description()));
    }

    @PostMapping("/performances")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceCreated createPerformance(@Valid @RequestBody CreatePerformanceRequest request) {
        return showAdminService.createPerformance(request.toCommand());
    }
}
