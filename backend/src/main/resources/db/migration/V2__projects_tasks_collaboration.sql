CREATE TABLE projects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    name varchar(150) NOT NULL,
    project_key varchar(10) NOT NULL CHECK(project_key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    description varchar(4000) NOT NULL DEFAULT '',
    status varchar(16) NOT NULL CHECK(status IN ('PLANNED','ACTIVE','ON_HOLD','COMPLETED','ARCHIVED')),
    visibility varchar(16) NOT NULL CHECK(visibility IN ('ORGANIZATION','PRIVATE')),
    priority varchar(10) NOT NULL CHECK(priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    start_date date,
    due_date date,
    created_by uuid NOT NULL REFERENCES users(id),
    next_task_number bigint NOT NULL DEFAULT 1 CHECK(next_task_number>0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    UNIQUE(organization_id,id),
    UNIQUE(organization_id,project_key),
    CHECK(due_date IS NULL OR start_date IS NULL OR due_date>=start_date)
);
CREATE INDEX project_tenant_status_idx ON projects(organization_id,status,created_at DESC,id);
CREATE TABLE project_members (
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role varchar(20) NOT NULL CHECK(role IN ('PROJECT_MANAGER','CONTRIBUTOR','VIEWER')),
    PRIMARY KEY(organization_id,project_id,user_id),
    FOREIGN KEY(organization_id,project_id) REFERENCES projects(organization_id,id),
    FOREIGN KEY(organization_id,user_id) REFERENCES organization_members(organization_id,user_id) ON DELETE CASCADE
);
CREATE INDEX project_member_user_idx ON project_members(organization_id,user_id,project_id);
CREATE TABLE tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    task_number bigint NOT NULL CHECK(task_number>0),
    title varchar(200) NOT NULL,
    description varchar(10000) NOT NULL DEFAULT '',
    status varchar(16) NOT NULL CHECK(status IN ('TODO','IN_PROGRESS','IN_REVIEW','BLOCKED','DONE')),
    priority varchar(10) NOT NULL CHECK(priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    reporter_id uuid NOT NULL REFERENCES users(id),
    assignee_id uuid,
    due_date date,
    estimated_effort numeric(8,2) CHECK(estimated_effort>=0),
    position integer NOT NULL DEFAULT 0 CHECK(position>=0),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    deleted_at timestamptz,
    UNIQUE(organization_id,id),
    UNIQUE(organization_id,project_id,task_number),
    UNIQUE(organization_id,project_id,id),
    FOREIGN KEY(organization_id,project_id) REFERENCES projects(organization_id,id),
    FOREIGN KEY(organization_id,assignee_id) REFERENCES organization_members(organization_id,user_id) ON DELETE SET NULL (assignee_id),
    FOREIGN KEY(organization_id,project_id,assignee_id) REFERENCES project_members(organization_id,project_id,user_id) ON DELETE SET NULL (assignee_id)
);
CREATE INDEX task_board_idx ON tasks(organization_id,project_id,status,position,id) WHERE deleted_at IS NULL;
CREATE INDEX task_assignee_due_idx ON tasks(organization_id,assignee_id,due_date,id) WHERE deleted_at IS NULL;
CREATE INDEX task_updated_idx ON tasks(organization_id,updated_at DESC,id) WHERE deleted_at IS NULL;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX task_title_search_idx ON tasks USING gin(lower(title) gin_trgm_ops);
CREATE INDEX project_name_search_idx ON projects USING gin(lower(name) gin_trgm_ops);
CREATE TABLE task_comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    task_id uuid NOT NULL,
    author_id uuid NOT NULL REFERENCES users(id),
    body varchar(5000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(organization_id,project_id,task_id) REFERENCES tasks(organization_id,project_id,id),
    FOREIGN KEY(organization_id,project_id) REFERENCES projects(organization_id,id)
);
CREATE INDEX task_comments_page_idx ON task_comments(organization_id,task_id,created_at,id);
CREATE TABLE task_activity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    task_id uuid,
    actor_id uuid NOT NULL REFERENCES users(id),
    action varchar(80) NOT NULL,
    detail varchar(300) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(organization_id,project_id) REFERENCES projects(organization_id,id),
    FOREIGN KEY(organization_id,project_id,task_id) REFERENCES tasks(organization_id,project_id,id)
);
CREATE INDEX task_activity_page_idx ON task_activity(organization_id,project_id,created_at DESC,id);
CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    project_id uuid,
    user_id uuid NOT NULL,
    message varchar(300) NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(organization_id,user_id) REFERENCES organization_members(organization_id,user_id) ON DELETE CASCADE,
    FOREIGN KEY(organization_id,project_id) REFERENCES projects(organization_id,id)
);
CREATE INDEX notification_user_page_idx ON notifications(organization_id,user_id,created_at DESC,id);
