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

-- Group memberships of human users (many-to-many: a user can be in several groups). The set of
-- group names is fixed in code (user/Groups.java) and mirrored in the Helm values' authorizations
-- (template-values-camunda-8.10.yaml); only the membership lives here. Stamped into user tokens as
-- the `groups` claim, which Orchestration reads via camunda.security.authentication.oidc.groups-claim.
CREATE TABLE IF NOT EXISTS user_groups (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    group_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (user_id, group_name)
);

-- User-managed OAuth2 client-credentials ("M2M") clients - see client/Client.java. Distinct from
-- the fixed, code-defined Camunda-component clients in config/OidcClientsConfig.java, which stay
-- in-memory on purpose and are never stored here.
--
-- "secret" is the plaintext secret, kept (not just its hash) so an admin can look it up again from
-- the UI at any time - deliberate for this demo-grade app (see the top-level CLAUDE.md), unlike
-- user passwords, which are never stored in recoverable form. "secret_hash" is still what's fed to
-- RegisteredClient/the OAuth2 client-authentication provider, which expects an encoded secret.
-- "audience" is what CompositeRegisteredClientRepository/AuthorizationServerConfig's token
-- customizer stamps into this client's access tokens as `aud` - see the note in
-- AuthorizationServerConfig.audiencesFor - so the client can be authorized against, e.g.,
-- Orchestration's REST API ("orchestration-api"), the same as the fixed "orchestration" and
-- "connectors" clients. Space/comma-separated for more than one audience.
CREATE TABLE IF NOT EXISTS oauth_clients (
    id UUID PRIMARY KEY,
    client_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    secret VARCHAR(255),
    secret_hash VARCHAR(255) NOT NULL,
    audience VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Both added after the initial release of this table (see git history) - plain ADD COLUMN IF NOT
-- EXISTS is a single statement, so, unlike the users-table column rename in
-- bootstrap/UsersTableMigration.java, it's safe straight in schema.sql without that class's
-- DO-block workaround.
ALTER TABLE oauth_clients ADD COLUMN IF NOT EXISTS secret VARCHAR(255);
ALTER TABLE oauth_clients ADD COLUMN IF NOT EXISTS audience VARCHAR(500);

-- User-managed "login clients": web apps outside the stack (for example the demo ticketing simulator) that log
-- users in with the authorization code flow + PKCE (public clients, no secret). Only relevant when Camunda
-- authorizations are enabled, see login/LoginClient.java. "redirect_uris" holds one allowed redirect URI
-- pattern per line ('*' matches any text, see login/RedirectPatterns.java); "audience" is stamped into the access
-- tokens like for the API clients above.
CREATE TABLE IF NOT EXISTS oauth_login_clients (
    id UUID PRIMARY KEY,
    client_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    audience VARCHAR(500),
    redirect_uris TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- No table for Secrets Management: Kubernetes is the source of truth there, not this database and
-- not an HTTP session either - see secret/Secret.java and web/AdminSecretController.java.
