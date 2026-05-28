package com.fintech.api.exception;

public class InviteExpiredException extends RuntimeException {
    public InviteExpiredException() {
        super("Este convite expirou");
    }
}
