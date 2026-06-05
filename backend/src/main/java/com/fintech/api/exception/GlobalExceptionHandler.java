package com.fintech.api.exception;

import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.CategoryHasTransactionsException;
import com.fintech.api.exception.InviteAlreadyUsedException;
import com.fintech.api.exception.InviteExpiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. SEU CÓDIGO ORIGINAL (Adaptado para a estrutura refinada)
    // Trata erros de validação (@Valid) - Retorna 400 com detalhes dos campos
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        // Sua lógica original de iteração mantida aqui:
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        // Envelopando na estrutura refinada
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Erro de Validação", errors);
    }

    // 2. SEU CÓDIGO ORIGINAL (Adaptado)
    // Trata erros de Regra de Negócio genéricos
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(IllegalArgumentException ex) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    // 3. NOVO CÓDIGO (A implementação refinada que você pediu)
    // Trata Entidade Não Encontrada (404)
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEntityNotFound(EntityNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    // 5. Trata Conflito de Negócio (409)
    @ExceptionHandler(BusinessConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(BusinessConflictException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    // 5b. Categoria com transações — 409 com campo extra transactionCount para o frontend
    @ExceptionHandler(CategoryHasTransactionsException.class)
    public ResponseEntity<Map<String, Object>> handleCategoryHasTransactions(CategoryHasTransactionsException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.CONFLICT.value());
        response.put("error", HttpStatus.CONFLICT.getReasonPhrase());
        response.put("message", ex.getMessage());
        response.put("transactionCount", ex.getTransactionCount());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    // 6. Trata Convite Já Utilizado (410 Gone)
    @ExceptionHandler(InviteAlreadyUsedException.class)
    public ResponseEntity<Map<String, Object>> handleInviteAlreadyUsed(InviteAlreadyUsedException ex) {
        return buildErrorResponse(HttpStatus.GONE, ex.getMessage(), null);
    }

    // 7. Trata Convite Expirado (410 Gone)
    @ExceptionHandler(InviteExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleInviteExpired(InviteExpiredException ex) {
        return buildErrorResponse(HttpStatus.GONE, ex.getMessage(), null);
    }

    // 4. NOVO CÓDIGO (Fallback de segurança)
    // Trata qualquer outro erro não mapeado (500)
    // Nota: NoResourceFoundException é excluída para deixar o Spring MVC tratar 404 de recursos estáticos
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex) throws NoResourceFoundException {
        if (ex instanceof NoResourceFoundException nrfe) {
            throw nrfe;
        }
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Ocorreu um erro interno no servidor.", null);
    }

    // --- MÉTODO AUXILIAR PARA PADRONIZAR O JSON ---
    private ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, String message, Map<String, String> details) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", status.value());
        response.put("error", status.getReasonPhrase());
        response.put("message", message);
        
        if (details != null && !details.isEmpty()) {
            response.put("details", details);
        }

        return ResponseEntity.status(status).body(response);
    }
}