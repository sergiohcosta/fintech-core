package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.dashboard.DashboardSummaryDTO;
import com.fintech.api.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryDTO> getSummary(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(service.getSummary(month, user));
    }
}
