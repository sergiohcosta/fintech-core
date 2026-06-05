package com.fintech.api.service;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MembersService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<MemberDTO> list(User currentUser) {
        return userRepository
                .findAllByTenantIdOrderByNameAsc(currentUser.getTenant().getId())
                .stream()
                .map(u -> new MemberDTO(u.getId(), u.getName(), u.getEmail(), u.getRole()))
                .toList();
    }
}
