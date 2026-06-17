package com.fintech.api.controller;

import com.fintech.api.config.LoginRateLimiter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.LoginDTO;
import com.fintech.api.dto.LoginResponseDTO;
import com.fintech.api.dto.RegisterResponseDTO;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.openapi.AuthApi;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import com.fintech.api.service.TenantRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final TenantRegistrationService registrationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final InvitationService invitationService;
    private final LoginRateLimiter loginRateLimiter;

    @Override
    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@RequestBody @Valid TenantRegistrationDTO dto) {
        Tenant newTenant = registrationService.register(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponseDTO(newTenant.getId(), newTenant.getName()));
    }

    @Override
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody @Valid LoginDTO data) {
        if (loginRateLimiter.isBlocked(data.email())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        var userOpt = this.userRepository.findByEmail(data.email());
        boolean authenticated = userOpt.isPresent()
                && userOpt.get().isEnabled()
                && passwordEncoder.matches(data.password(), userOpt.get().getPasswordHash());

        if (!authenticated) {
            loginRateLimiter.registerFailure(data.email());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        loginRateLimiter.registerSuccess(data.email());
        String token = tokenService.generateToken(userOpt.get());
        return ResponseEntity.ok(new LoginResponseDTO(token));
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<LoginResponseDTO> acceptInvite(@RequestBody @Valid AcceptInviteDTO dto) {
        String token = invitationService.accept(dto);
        return ResponseEntity.ok(new LoginResponseDTO(token));
    }
}