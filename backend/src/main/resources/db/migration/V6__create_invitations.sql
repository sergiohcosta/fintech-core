CREATE TABLE invitations (
    id          UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    email       VARCHAR(255) NOT NULL,
    token       VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_invitations_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uk_invitations_token  UNIQUE (token)
);

CREATE INDEX idx_invitations_token        ON invitations(token);
CREATE INDEX idx_invitations_tenant_email ON invitations(tenant_id, email);
