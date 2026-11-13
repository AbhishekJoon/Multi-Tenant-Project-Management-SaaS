# Initial threat model

| Threat | Control | Required evidence |
|---|---|---|
| Tenant ID/resource guessing | Current membership + tenant-scoped queries + composite foreign keys | Cross-tenant integration tests |
| Private project disclosure | Central project access policy used by lists, tasks and aggregates | Project role tests |
| Privilege escalation | Explicit permission matrix, owner-only transfer, locked owner transitions | Role and final-owner tests |
| Session theft/replay | Supabase token verification and session/ban/deletion checks, HttpOnly refresh cookie; local token digests/rotation | Provider rejection and replay/logout tests |
| Email link/account substitution | PKCE with HttpOnly verifier, fixed callback, confirmed email before preserving a legacy account's ID | Callback, unconfirmed-email and identity-link tests |
| CSRF on refresh | Exact origin guard, required custom header, SameSite cookie | Invalid-origin tests |
| Password guessing | Supabase Auth controls, backend throttling/generic errors; BCrypt in Local mode | Authentication tests |
| Invitation takeover | Email-bound expiring single-use hashed tokens | Wrong-user and replay tests |
| Stale task writes | Version predicate and 409 response | Concurrent update tests |
| Sensitive logging | Never log credentials or request bodies; correlation ID only | Code review |

Deployment assumes HTTPS, trusted reverse proxy, restricted database access and a single application replica for the in-memory rate limiter. Multi-replica deployment requires a shared limiter. Compromised application/database administrator access is outside the tenant API boundary; database backups and production operator access must be restricted.
