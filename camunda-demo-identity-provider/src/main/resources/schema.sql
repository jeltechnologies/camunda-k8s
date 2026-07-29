CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default_admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- User-managed OAuth2 client-credentials ("M2M") clients - see client/Client.java. Distinct from
-- the fixed, code-defined Camunda-component clients in config/OidcClientsConfig.java, which stay
-- in-memory on purpose and are never stored here.
CREATE TABLE IF NOT EXISTS oauth_clients (
    id UUID PRIMARY KEY,
    client_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    secret_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
