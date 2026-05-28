package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.service.InvitationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/invites")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    public ResponseEntity<InvitationResponseDTO> create(@RequestBody @Valid CreateInvitationDTO dto) {
        User admin = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.create(dto, admin));
    }

    @GetMapping("/{token}")
    public ResponseEntity<InvitationInfoDTO> validate(@PathVariable String token) {
        return ResponseEntity.ok(invitationService.validate(token));
    }
}
