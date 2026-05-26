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
import org.springframework.security.core.context.SecurityContextHolder;
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

    // Obtém o usuário autenticado via SecurityContextHolder em vez de @AuthenticationPrincipal,
    // pois a interface OpenAPI não inclui esse parâmetro extra nas assinaturas dos métodos.
    private User getAuthenticatedUser() {
        UserDetails userDetails = (UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));
    }
}
