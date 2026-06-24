package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.service.MembersService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import com.fintech.api.config.SecurityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MembersController {

    private final MembersService membersService;

    @GetMapping
    public ResponseEntity<List<MemberDTO>> list() {
        User currentUser = SecurityUtils.currentUser();
        return ResponseEntity.ok(membersService.list(currentUser));
    }
}
