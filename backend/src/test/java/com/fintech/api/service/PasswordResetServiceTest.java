package com.fintech.api.service;

import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.passwordreset.PasswordResetToken;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.ForgotPasswordDTO;
import com.fintech.api.dto.ResetPasswordDTO;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.repository.PasswordResetTokenRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock EmailService emailService;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks PasswordResetService service;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:4200");

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("carlos@costa.com");
        user.setRole(UserRole.USER);
        user.setTenant(tenant);
        user.setActive(true);
        user.setPasswordHash("hash-antigo");
    }

    @Test
    @DisplayName("requestReset gera token e envia email quando usuário existe e está ativo")
    void requestReset_existingActiveUser_sendsEmailWithToken() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        service.requestReset(new ForgotPasswordDTO(user.getEmail()));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        PasswordResetToken saved = tokenCaptor.getValue();
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getToken()).isNotBlank();
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(saved.isUsed()).isFalse();

        verify(emailService).sendPasswordResetEmail(eq(user.getEmail()), contains(saved.getToken()));
    }

    @Test
    @DisplayName("requestReset não lança e não envia email quando usuário não existe (anti-enumeração)")
    void requestReset_unknownEmail_doesNothingSilently() {
        when(userRepository.findByEmail("ninguem@teste.com")).thenReturn(Optional.empty());

        service.requestReset(new ForgotPasswordDTO("ninguem@teste.com"));

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("requestReset não propaga exceção quando o envio de email falha (SMTP fora do ar)")
    void requestReset_emailSendFails_doesNotPropagate() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        doThrow(new MailSendException("conexão recusada"))
                .when(emailService).sendPasswordResetEmail(anyString(), anyString());

        assertThatCode(() -> service.requestReset(new ForgotPasswordDTO(user.getEmail())))
                .doesNotThrowAnyException();

        // Token já foi persistido antes da tentativa de envio — usuário pode pedir de novo.
        verify(tokenRepository).save(any());
    }

    @Test
    @DisplayName("requestReset não envia email quando usuário existe mas está inativo")
    void requestReset_inactiveUser_doesNotSendEmail() {
        user.setActive(false);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        service.requestReset(new ForgotPasswordDTO(user.getEmail()));

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    private PasswordResetToken buildToken(boolean used, LocalDateTime expiresAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken("token-valido");
        token.setUsed(used);
        token.setExpiresAt(expiresAt);
        return token;
    }

    @Test
    @DisplayName("reset troca o hash, marca passwordChangedAt e invalida o token")
    void reset_validToken_changesPasswordAndInvalidatesToken() {
        PasswordResetToken token = buildToken(false, LocalDateTime.now().plusMinutes(30));
        when(tokenRepository.findByToken("token-valido")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NovaSenha123")).thenReturn("hash-novo");

        service.reset(new ResetPasswordDTO("token-valido", "NovaSenha123"));

        assertThat(user.getPasswordHash()).isEqualTo("hash-novo");
        assertThat(user.getPasswordChangedAt()).isNotNull();
        verify(userRepository).save(user);
        assertThat(token.isUsed()).isTrue();
        verify(tokenRepository).save(token);
    }

    @Test
    @DisplayName("reset rejeita token inexistente")
    void reset_unknownToken_throwsBusinessException() {
        when(tokenRepository.findByToken("nao-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset(new ResetPasswordDTO("nao-existe", "NovaSenha123")))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("reset rejeita token já usado")
    void reset_usedToken_throwsBusinessException() {
        PasswordResetToken token = buildToken(true, LocalDateTime.now().plusMinutes(30));
        when(tokenRepository.findByToken("token-valido")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.reset(new ResetPasswordDTO("token-valido", "NovaSenha123")))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("reset rejeita token expirado")
    void reset_expiredToken_throwsBusinessException() {
        PasswordResetToken token = buildToken(false, LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findByToken("token-valido")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.reset(new ResetPasswordDTO("token-valido", "NovaSenha123")))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }
}
