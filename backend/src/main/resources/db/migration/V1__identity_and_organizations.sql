CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    name varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    email_verified boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    family_id uuid NOT NULL,
    token_hash varchar(64) NOT NULL UNIQUE,
    access_hash varchar(64) NOT NULL UNIQUE,
    access_expires_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used boolean NOT NULL DEFAULT false,
    revoked boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_family_idx ON refresh_tokens(family_id);
CREATE INDEX refresh_user_idx ON refresh_tokens(user_id);
CREATE TABLE password_reset_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used boolean NOT NULL DEFAULT false
);
CREATE TABLE email_verification_tokens (LIKE password_reset_tokens INCLUDING ALL);
ALTER TABLE email_verification_tokens ADD FOREIGN KEY (user_id) REFERENCES users(id);
CREATE TABLE organizations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL,
    description varchar(2000) NOT NULL DEFAULT '',
    logo_url varchar(1000),
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz
);
CREATE TABLE organization_members (
    organization_id uuid NOT NULL REFERENCES organizations(id),
    user_id uuid NOT NULL REFERENCES users(id),
    role varchar(16) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(organization_id,user_id)
);
CREATE INDEX org_members_user_idx ON organization_members(user_id,organization_id);
CREATE TABLE organization_invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    email varchar(254) NOT NULL,
    role varchar(16) NOT NULL CHECK(role IN ('ADMIN','MEMBER','VIEWER')),
    token_hash varchar(64) NOT NULL UNIQUE,
    invited_by uuid NOT NULL REFERENCES users(id),
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','REJECTED','REVOKED')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(organization_id,id)
);
CREATE UNIQUE INDEX org_invitation_pending_idx ON organization_invitations(organization_id,email) WHERE status='PENDING';
CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES organizations(id),
    actor_id uuid NOT NULL REFERENCES users(id),
    action varchar(80) NOT NULL,
    resource_type varchar(40) NOT NULL,
    resource_id uuid,
    metadata jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_tenant_date_idx ON audit_logs(organization_id,created_at DESC,id);
