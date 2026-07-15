package com.example.ticketing.dashboard.controller;

import com.example.ticketing.dashboard.domain.LockVersion;
import com.example.ticketing.dashboard.domain.ScenarioType;
import com.example.ticketing.dashboard.domain.TestResult;
import com.example.ticketing.dashboard.dto.ChartData;
import com.example.ticketing.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public String dashboard(
            @RequestParam(required = false) ScenarioType scenario,
            @RequestParam(required = false) LockVersion version,
            @RequestParam(required = false) Integer users,
            Model model) {

        List<TestResult> filtered = dashboardService.findByFilters(scenario, version, users);
        ChartData chartData = buildChartData(scenario, users);

        model.addAttribute("results", filtered);
        model.addAttribute("chartData", chartData);
        model.addAttribute("selectedScenario", scenario);
        model.addAttribute("selectedVersion", version);
        model.addAttribute("selectedUsers", users);
        model.addAttribute("scenarioTypes", ScenarioType.values());
        model.addAttribute("lockVersions", LockVersion.values());
        model.addAttribute("userOptions", List.of(500, 2000));

        return "dashboard/index";
    }

    private ChartData buildChartData(ScenarioType scenario, Integer users) {
        ScenarioType targetScenario = scenario != null ? scenario : ScenarioType.SCENARIO_A;
        int targetUsers = users != null ? users : 500;

        List<TestResult> relevant = dashboardService.findLatestByScenarioAndUsers(targetScenario, targetUsers);

        List<String> labels = new ArrayList<>();
        List<Double> tpsList = new ArrayList<>();
        List<Long> p99List = new ArrayList<>();
        List<Integer> overBookingList = new ArrayList<>();
        List<Double> errorRateList = new ArrayList<>();

        for (TestResult r : relevant) {
            labels.add(r.getVersion().name() + " (" + r.getLockType().name() + ")");
            tpsList.add(r.getTps());
            p99List.add(r.getP99ResponseMs());
            overBookingList.add(r.getOverBookingCount());
            errorRateList.add(r.getErrorRate());
        }

        return new ChartData(labels, tpsList, p99List, overBookingList, errorRateList);
    }
}
