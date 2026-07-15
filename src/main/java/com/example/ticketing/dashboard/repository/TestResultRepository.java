package com.example.ticketing.dashboard.repository;

import com.example.ticketing.dashboard.domain.LockVersion;
import com.example.ticketing.dashboard.domain.ScenarioType;
import com.example.ticketing.dashboard.domain.TestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TestResultRepository extends JpaRepository<TestResult, Long> {

    List<TestResult> findAllByOrderByTestedAtDesc();

    List<TestResult> findByScenarioTypeOrderByTestedAtDesc(ScenarioType scenarioType);

    @Query("SELECT t FROM TestResult t WHERE t.id IN (" +
           "SELECT MAX(t2.id) FROM TestResult t2 " +
           "WHERE t2.scenarioType = :scenario AND t2.concurrentUsers = :users " +
           "GROUP BY t2.version) ORDER BY t.version")
    List<TestResult> findLatestByScenarioAndUsers(@Param("scenario") ScenarioType scenario,
                                                  @Param("users") int users);

    @Query("SELECT t FROM TestResult t WHERE " +
           "(:scenario IS NULL OR t.scenarioType = :scenario) AND " +
           "(:version IS NULL OR t.version = :version) AND " +
           "(:users IS NULL OR t.concurrentUsers = :users) " +
           "ORDER BY t.testedAt DESC")
    List<TestResult> findByFilters(@Param("scenario") ScenarioType scenario,
                                   @Param("version") LockVersion version,
                                   @Param("users") Integer users);
}
