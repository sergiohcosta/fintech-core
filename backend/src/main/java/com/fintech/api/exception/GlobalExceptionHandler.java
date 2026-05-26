package com.fintech.api.exception;

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

    // 4. NOVO CÓDIGO (Fallback de segurança)
    // Trata qualquer outro erro não mapeado (500)
    // Nota: NoResourceFoundException é excluída para deixar o Spring MVC tratar 404 de recursos estáticos
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex) throws NoResourceFoundException {
        if (ex instanceof NoResourceFoundException nrfe) {
            throw nrfe;
        }
        // Em produção, é bom logar o 'ex' aqui
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