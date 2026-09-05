CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);

ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP NULL;
