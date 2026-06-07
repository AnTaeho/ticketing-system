package com.example.ticketing.dashboard.controller;

import com.example.ticketing.dashboard.domain.ScenarioType;
import com.example.ticketing.dashboard.domain.TestResult;
import com.example.ticketing.dashboard.dto.TestResultRequest;
import com.example.ticketing.dashboard.service.DashboardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/test-results")
@RequiredArgsConstructor
public class TestResultController {

    private final DashboardService dashboardService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TestResult save(@Valid @RequestBody TestResultRequest request) {
        return dashboardService.save(request);
    }

    @GetMapping
    public List<TestResult> findAll(@RequestParam(required = false) ScenarioType scenario) {
        if (scenario != null) {
            return dashboardService.findByScenario(scenario);
        }
        return dashboardService.findAll();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        dashboardService.delete(id);
    }
}
