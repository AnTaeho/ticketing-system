package com.example.ticketing.dashboard.service;

import com.example.ticketing.dashboard.domain.LockVersion;
import com.example.ticketing.dashboard.domain.ScenarioType;
import com.example.ticketing.dashboard.domain.TestResult;
import com.example.ticketing.dashboard.dto.TestResultRequest;
import com.example.ticketing.dashboard.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TestResultRepository testResultRepository;

    @Transactional
    public TestResult save(TestResultRequest request) {
        TestResult result = TestResult.of(
                request.version(), request.lockType(), request.scenarioType(),
                request.concurrentUsers(), request.initialStock(), request.totalRequests(),
                request.successCount(), request.overBookingCount(), request.tps(),
                request.p99ResponseMs(), request.errorRate(), request.memo()
        );
        return testResultRepository.save(result);
    }

    public List<TestResult> findAll() {
        return testResultRepository.findAllByOrderByTestedAtDesc();
    }

    public List<TestResult> findByScenario(ScenarioType scenarioType) {
        return testResultRepository.findByScenarioTypeOrderByTestedAtDesc(scenarioType);
    }

    public List<TestResult> findByFilters(ScenarioType scenario, LockVersion version, Integer users) {
        return testResultRepository.findByFilters(scenario, version, users);
    }

    public List<TestResult> findLatestByScenarioAndUsers(ScenarioType scenario, int users) {
        return testResultRepository.findLatestByScenarioAndUsers(scenario, users);
    }

    @Transactional
    public void delete(Long id) {
        testResultRepository.deleteById(id);
    }
}
