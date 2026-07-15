package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.dashboard.DashboardSummaryDTO;
import com.fintech.api.openapi.DashboardApi;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.DashboardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import com.fintech.api.config.SecurityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController implements DashboardApi {

    private final DashboardService service;
    private final UserRepository userRepository;

    @Override
    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryDTO> getDashboardSummary(
            @NotNull @Valid @RequestParam String month
    ) {
        // A interface OpenAPI define month como String (formato yyyy-MM).
        // Convertemos para YearMonth aqui para manter a lógica do service inalterada.
        YearMonth yearMonth = YearMonth.parse(month);
        return ResponseEntity.ok(service.getSummary(yearMonth, getAuthenticatedUser()));
    }

    private User getAuthenticatedUser() {
        return SecurityUtils.currentUser();
    }
}
