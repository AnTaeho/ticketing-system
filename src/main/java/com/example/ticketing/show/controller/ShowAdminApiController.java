package com.example.ticketing.show.controller;

import com.example.ticketing.show.dto.CreatePerformanceRequest;
import com.example.ticketing.show.dto.CreatePerformanceResponse;
import com.example.ticketing.show.dto.CreateShowRequest;
import com.example.ticketing.show.dto.CreateShowResponse;
import com.example.ticketing.show.service.ShowAdminService;
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
    public CreateShowResponse createShow(@Valid @RequestBody CreateShowRequest request) {
        return CreateShowResponse.from(showAdminService.createShow(request.toCommand()));
    }

    @PostMapping("/performances")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePerformanceResponse createPerformance(@Valid @RequestBody CreatePerformanceRequest request) {
        return CreatePerformanceResponse.from(showAdminService.createPerformance(request.toCommand()));
    }
}
