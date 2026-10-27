package com.example.ticketing.booking.hold.api;

import com.example.ticketing.booking.common.CurrentUser;
import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.dto.ActiveHold;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.dto.HoldDetail;
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
    public HoldCreated hold(@CurrentUser Long userId, @PathVariable long performanceId,
                            @RequestBody CreateHoldRequest request) {
        return seatHoldService.hold(userId, performanceId, request.seatIds(), request.queueToken());
    }

    @GetMapping("/api/performances/{performanceId}/holds/mine")
    public ActiveHold mine(@CurrentUser Long userId, @PathVariable long performanceId) {
        return seatHoldService.getActiveHold(userId, performanceId);
    }

    @GetMapping("/api/holds/{holdId}")
    public HoldDetail get(@CurrentUser Long userId, @PathVariable long holdId) {
        return seatHoldService.getHold(userId, holdId);
    }

    @DeleteMapping("/api/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@CurrentUser Long userId, @PathVariable long holdId) {
        seatHoldService.release(userId, holdId);
    }
}
