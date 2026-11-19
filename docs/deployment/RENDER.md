# Deploy TeamFlow to teamflow.abhishekjoon.com

The Render Blueprint runs React and Spring Boot in one Docker web service. Nginx serves the React build and forwards same-origin API requests to Java on loopback. The Supabase profile uses the existing PostgreSQL and Auth project. The local Compose workflow is separate.

## Configure Supabase and email

1. Check that the dedicated TeamFlow project has the private schema from [Supabase setup](SUPABASE.md) and back it up before deploying migrations. Use the Session pooler on port 5432 and its matching CA.
2. Set the [Supabase Auth Site URL and redirect allowlist](https://supabase.com/docs/guides/auth/redirect-urls). Set Site URL to `https://teamflow.abhishekjoon.com` and add both `https://teamflow.abhishekjoon.com/auth/callback?flow=signup` and `https://teamflow.abhishekjoon.com/auth/callback?flow=recovery`. Retain exact development callbacks if the project still serves local development.
3. Configure [custom SMTP in Supabase Auth](https://supabase.com/docs/guides/auth/auth-smtp) for signup and password recovery. Supply the same or a separate transactional SMTP account to Render for workspace invitations. Render Free blocks outbound ports 25, 465 and 587. The Blueprint uses STARTTLS on port 2525, supported by [Brevo SMTP relay](https://help.brevo.com/hc/en-us/articles/10905415650322-Which-SMTP-port-should-I-use-Port-587-465-or-2525).

## Create the service

1. In Render, choose **New → Blueprint** and select the repository and branch containing `render.yaml`. Enter the Supabase connection values and SMTP credentials in the Render dashboard.
2. In the new service, open **Environment → Secret Files** and add `supabase-ca.crt` with the CA contents. The initial attempt can fail until this file is present. Redeploy after saving it.
3. Wait for `/actuator/health` on the assigned `onrender.com` URL to return UP. Use the custom domain for browser sign-in because `APP_ORIGIN` pins state-changing requests to that hostname.

## Connect Cloudflare and verify

1. In Cloudflare DNS for `abhishekjoon.com`, add a DNS-only CNAME named `teamflow` targeting the exact host shown by Render. Verify the custom domain in Render and wait for the certificate. See [Render's Cloudflare DNS instructions](https://render.com/docs/configure-cloudflare-dns).
2. On the custom HTTPS hostname, check `/actuator/health`, a direct load of `/login`, and an unauthenticated request to `/api/v1/auth/me` (401). Use dedicated accounts to test signup, verification, logout/login and refresh, invitation delivery, and persistence after moving a task.
3. Verify signup and recovery emails in the same browser where the flows started. Keep SMTP secrets in provider settings. After the custom domain works, the spare Render hostname can be disabled in its dashboard.

Render Free should be used for a portfolio demo. It can sleep after inactivity and its memory capacity needs verification under the deployed workload. Nginx replaces user-supplied forwarding headers and passes the ingress peer IP to the existing application limiter. Visitors may share one limiter bucket behind a shared ingress. Verify attribution or provide a trusted edge rate limit before using this setup for regular public traffic.
