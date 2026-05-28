package com.fintech.api.exception;

public class InviteAlreadyUsedException extends RuntimeException {
    public InviteAlreadyUsedException() {
        super("Este convite já foi utilizado");
    }
}
