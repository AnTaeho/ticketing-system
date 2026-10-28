package com.example.ticketing.hold.controller;

import com.example.ticketing.common.web.CurrentUser;
import com.example.ticketing.hold.dto.ActiveHoldResponse;
import com.example.ticketing.hold.dto.CreateHoldRequest;
import com.example.ticketing.hold.dto.CreateHoldResponse;
import com.example.ticketing.hold.dto.HoldDetailResponse;
import com.example.ticketing.hold.service.SeatHoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HoldApiController {

    private final SeatHoldService seatHoldService;

    @PostMapping("/api/performances/{performanceId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateHoldResponse hold(@CurrentUser Long userId, @PathVariable long performanceId,
                                   @RequestBody CreateHoldRequest request) {
        return CreateHoldResponse.from(seatHoldService.hold(request.toCommand(userId, performanceId)));
    }

    @GetMapping("/api/performances/{performanceId}/holds/mine")
    public ActiveHoldResponse mine(@CurrentUser Long userId, @PathVariable long performanceId) {
        return ActiveHoldResponse.from(seatHoldService.getActiveHold(userId, performanceId));
    }

    @GetMapping("/api/holds/{holdId}")
    public HoldDetailResponse get(@CurrentUser Long userId, @PathVariable long holdId) {
        return HoldDetailResponse.from(seatHoldService.getHold(userId, holdId));
    }

    @DeleteMapping("/api/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@CurrentUser Long userId, @PathVariable long holdId) {
        seatHoldService.release(userId, holdId);
    }
}
