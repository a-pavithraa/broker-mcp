# Broker MCP Server — Deployment Plan

Three deployment tracks for running the MCP server remotely so Claude mobile app can reach it via HTTPS.

All tracks share the same local cron setup: your Windows machine runs `refresh_trading_sessions.sh` daily, then pushes fresh tokens to the target environment.

## Authentication Strategy

Claude mobile accesses remote MCP servers via **Connectors** (Settings > Connectors). Connectors configured on claude.ai or Claude Desktop sync to mobile automatically.

Connectors require **OAuth 2.1 with PKCE** per the MCP authorization spec. The MCP server must expose:
- `/.well-known/oauth-protected-resource` — resource metadata
- `/.well-known/oauth-authorization-server` — authorization server metadata
- `/authorize` — authorization endpoint (delegates to Google)
- `/token` — token endpoint
- `/register` — Dynamic Client Registration (Claude registers itself)

| Track | OAuth Implementation |
|-------|---------------------|
| **VPS** | Spring Security OAuth2 Authorization Server in the app, Google as upstream IdP |
| **ECS** | AWS Cognito (Google federation) + ALB authenticate action |
| **EKS** | AWS Cognito (Google federation) + ALB Ingress annotations |

## Current Status And Deferred Plan

Current repo status:
- The existing `http` profile exposes the MCP endpoint for streamable HTTP transport.
- The current application build does not yet enforce app-level authentication or authorization for that endpoint.
- This document is the deferred implementation plan for making internet-facing HTTP deployment safe.

Until this plan is completed:
- Do not expose `/mcp` directly to the public internet.
- Keep HTTP deployments behind localhost, a VPN, or an authenticated reverse proxy.
- Prefer `BROKER_TOOLS_MODE=readonly` for any HTTP deployment.
- Treat session-write and trade-execution tools as high-risk until request authentication is enforced.

Recommended execution order:
1. Add Spring Security and require authentication for all HTTP MCP requests.
2. Choose the remote auth model by deployment target.
   VPS: in-app OAuth authorization server with Google as upstream identity provider.
   ECS/EKS: Cognito-backed auth with MCP-compatible discovery and token flow.
3. Add MCP auth-discovery endpoints and Claude connector validation flow.
4. Add operational hardening: reverse-proxy auth enforcement, TLS, rate limiting, reduced log verbosity, and write-tool restrictions by environment.
5. Only then expose the service publicly.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        LOCAL MACHINE (Windows)                      │
│                                                                     │
│  Task Scheduler (daily, ~8:30 IST)                                  │
│   └─ refresh_trading_sessions.sh                                    │
│       ├─ Playwright: browser login → tokens                         │
│       └─ push-session.sh                                            │
│            ├─ [VPS]  scp + ssh restart                              │
│            └─ [AWS]  aws secretsmanager put-secret-value            │
└─────────────────────────────────────────────────────────────────────┘
         │                        │                       │
         ▼                        ▼                       ▼
   ┌───────────┐          ┌──────────────┐        ┌──────────────┐
   │    VPS    │          │  ECS Fargate  │        │     EKS      │
   │  nginx    │          │  ALB+Cognito  │        │  ALB Ingress │
   │  + TLS    │          │  + ACM        │        │  + Cognito   │
   │  + Spring │          │  + Secrets Mgr│        │  + ExtSecrets│
   │    OAuth  │          │               │        │              │
   └───────────┘          └──────────────┘        └──────────────┘
         ▲                        ▲                       ▲
         │               Google OAuth 2.1                 │
         └──────────── Claude Connector (mobile) ─────────┘
```

---

## Shared: Local Cron — Token Refresh + Push

Runs on your Windows machine. The broker login requires Playwright (Chromium) and potentially manual 2FA, so it stays local.

### Prerequisites

- `refresh_trading_sessions.sh` working locally
- `ZERODHA_TOTP_SECRET` set (automated 2FA) — or be present for manual entry
- `GMAIL_USER` + `GMAIL_APP_PASSWORD` set (automated Breeze OTP) — or be present for manual entry

### push-session-vps.sh (VPS track)

```bash
#!/usr/bin/env bash
set -euo pipefail

VPS_HOST="deploy@mcp.yourdomain.com"
VPS_ENV_PATH="/opt/broker-mcp/.env.session"

# 1. Refresh tokens locally
./refresh_trading_sessions.sh

# 2. Push to VPS
scp ~/.broker-mcp/.env.session "$VPS_HOST:$VPS_ENV_PATH"

# 3. Restart container
ssh "$VPS_HOST" "docker restart broker-mcp"

echo "Session pushed and container restarted."
```

### push-session-aws.sh (ECS / EKS track)

```bash
#!/usr/bin/env bash
set -euo pipefail

SECRET_ID="broker-mcp/session"
REGION="ap-south-1"
ECS_CLUSTER="broker-mcp"
ECS_SERVICE="broker-mcp"

# 1. Refresh tokens locally
./refresh_trading_sessions.sh

# 2. Read .env.session into JSON
ENV_FILE="$HOME/.broker-mcp/.env.session"
JSON_PAYLOAD=$(python3 -c "
import json, sys
d = {}
for line in open(sys.argv[1]):
    line = line.strip()
    if line and not line.startswith('#') and '=' in line:
        k, v = line.split('=', 1)
        d[k.strip()] = v.strip()
print(json.dumps(d))
" "$ENV_FILE")

# 3. Push to Secrets Manager
aws secretsmanager put-secret-value \
  --secret-id "$SECRET_ID" \
  --secret-string "$JSON_PAYLOAD" \
  --region "$REGION"

# 4. Force new deployment (pulls latest secret)
aws ecs update-service \
  --cluster "$ECS_CLUSTER" \
  --service "$ECS_SERVICE" \
  --force-new-deployment \
  --region "$REGION" > /dev/null

echo "Secret updated and ECS service redeployment triggered."
```

For EKS, replace step 4 with:
```bash
kubectl rollout restart deployment/broker-mcp -n broker-mcp
```

Or rely on ExternalSecrets + Reloader to detect the change automatically (see EKS section).

### Windows Task Scheduler

```powershell
# Run daily at 08:30 IST via WSL
$action = New-ScheduledTaskAction -Execute "wsl" -Argument "bash -lc '/mnt/d/pills-ai/broker-mcp/push-session-aws.sh'"
$trigger = New-ScheduledTaskTrigger -Daily -At "08:30"
Register-ScheduledTask -TaskName "BrokerMCP-RefreshSession" -Action $action -Trigger $trigger -Description "Refresh broker tokens and push to deployment target"
```

---

## Track A: VPS Deployment

Cheapest option (~$5-10/mo). Full control, minimal moving parts.

Auth: Spring Security OAuth2 Authorization Server baked into the app, delegating identity to Google.

### Architecture

```
Claude Mobile                 VPS (nginx + app)              Google
    │                              │                           │
    ├─ GET /.well-known/oauth-* ──►│                           │
    │◄─ discovery metadata ────────│                           │
    │                              │                           │
    ├─ POST /register ────────────►│ (DCR: Claude registers)   │
    │◄─ client_id + client_secret ─│                           │
    │                              │                           │
    ├─ GET /authorize ────────────►│                           │
    │                              ├─ redirect to Google ─────►│
    │                              │                           │
    │◄──── Google login page ──────┼───────────────────────────│
    │──── user logs in ────────────┼──────────────────────────►│
    │                              │                           │
    │                              │◄─ auth code ──────────────│
    │                              ├─ exchange for Google token │
    │◄─ redirect with MCP code ────│                           │
    │                              │                           │
    ├─ POST /token (code+PKCE) ───►│                           │
    │◄─ MCP access_token ─────────│                           │
    │                              │                           │
    ├─ MCP requests + Bearer ─────►│ (validates own token)     │
```

### 1. VPS Provisioning

- Ubuntu 24.04 LTS, 2GB RAM minimum
- Providers: Hetzner, DigitalOcean, Linode — pick based on region latency

### 2. SSH Hardening

```bash
# On VPS as root
adduser deploy
usermod -aG docker deploy

# Copy your public key
mkdir -p /home/deploy/.ssh
echo "YOUR_PUBLIC_KEY" >> /home/deploy/.ssh/authorized_keys
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys
chown -R deploy:deploy /home/deploy/.ssh

# Disable password auth
sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/#PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
systemctl restart sshd
```

### 3. Firewall

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp    # SSH
ufw allow 443/tcp   # HTTPS
ufw enable
```

Port 8081 is NOT opened — nginx proxies internally.

### 4. Docker Setup

```bash
apt update && apt install -y docker.io
systemctl enable docker
```

### 5. Google OAuth Credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/) > APIs & Services > Credentials
2. Create OAuth 2.0 Client ID (Web application)
3. Authorized redirect URI: `https://mcp.yourdomain.com/login/oauth2/code/google`
4. Note the **Client ID** and **Client Secret**

### 6. Spring Security OAuth2 Changes (app code)

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-authorization-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

Add `application-oauth.properties`:

```properties
# Google as upstream IdP
spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}
spring.security.oauth2.client.registration.google.scope=openid,email

# Restrict to your Google account
broker.oauth.allowed-email=${BROKER_OAUTH_ALLOWED_EMAIL}
```

Implementation needed:
- `SecurityConfig` — configure OAuth2 login + authorization server endpoints
- `McpOAuthDiscoveryController` — serves `/.well-known/oauth-protected-resource` and `/.well-known/oauth-authorization-server`
- `DynamicClientRegistrationEndpoint` — handles `/register` (Claude registers itself on first connect)
- Email allowlist filter — only your Google email can authorize

This is a non-trivial code change. Estimated effort: 1-2 days.

### 7. Application Deployment

```bash
mkdir -p /opt/broker-mcp/data
chown deploy:deploy /opt/broker-mcp /opt/broker-mcp/data

docker run -d \
  --name broker-mcp \
  --restart unless-stopped \
  -p 127.0.0.1:8081:8081 \
  --env-file /opt/broker-mcp/.env.session \
  -e SPRING_PROFILES_ACTIVE=http,docker,oauth \
  -e GOOGLE_CLIENT_ID=your_google_client_id \
  -e GOOGLE_CLIENT_SECRET=your_google_client_secret \
  -e BROKER_OAUTH_ALLOWED_EMAIL=your.email@gmail.com \
  -v /opt/broker-mcp/data:/data \
  pavithravasudevan/broker-mcp
```

### 8. Nginx + TLS

```bash
apt install -y nginx certbot python3-certbot-nginx
```

Nginx config (`/etc/nginx/sites-available/broker-mcp`):

```nginx
limit_req_zone $binary_remote_addr zone=mcp_limit:10m rate=10r/s;

server {
    listen 443 ssl http2;
    server_name mcp.yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/mcp.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/mcp.yourdomain.com/privkey.pem;
    ssl_protocols TLSv1.3;
    ssl_prefer_server_ciphers off;

    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;
    add_header X-Frame-Options DENY always;
    add_header X-Content-Type-Options nosniff always;

    # MCP endpoint — OAuth-protected at the app layer
    location /mcp {
        limit_req zone=mcp_limit burst=20 nodelay;

        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400s;
    }

    # OAuth endpoints — must be accessible for the auth flow
    location ~ ^/(\.well-known|oauth2|login|authorize|token|register) {
        limit_req zone=mcp_limit burst=10 nodelay;

        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        return 404;
    }
}

server {
    listen 80;
    server_name mcp.yourdomain.com;
    return 301 https://$host$request_uri;
}
```

```bash
ln -s /etc/nginx/sites-available/broker-mcp /etc/nginx/sites-enabled/
rm /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
certbot --nginx -d mcp.yourdomain.com
```

### 9. Claude Connector Setup

1. Go to claude.ai > Settings > Connectors
2. Click "Add custom connector"
3. Enter URL: `https://mcp.yourdomain.com/mcp`
4. Claude initiates OAuth → redirects to Google login → you sign in
5. Connector syncs to mobile automatically

### VPS Security Summary

| Layer | What |
|-------|------|
| Network | UFW: only 22 + 443. Docker bound to 127.0.0.1 |
| Transport | TLS 1.3, HSTS, auto-renew via certbot |
| Auth | OAuth 2.1 + PKCE via Spring Security, Google as IdP |
| Identity | Email allowlist — only your Google account can authorize |
| Rate limit | nginx: 10 req/s per IP, burst 20 |
| SSH | Key-only, no root, no password |
| Credentials | `.env.session` mode 600, deploy user only |
| App | `readonly` mode by default |

---

## Track B: ECS Fargate Deployment

AWS-managed containers. No servers to patch. Cognito handles OAuth natively — zero app code changes.

### Architecture

```
Claude Mobile              ALB + Cognito              ECS Task         Google
    │                          │                         │               │
    ├─ HTTPS request ─────────►│                         │               │
    │                          │                         │               │
    │  (no valid session)      │                         │               │
    │◄─ 302 redirect ─────────│─── Cognito hosted UI ──►│               │
    │                          │         │               │               │
    │                          │         ├─ federated ──►│──► Google ───►│
    │                          │         │               │               │
    │──── Google login ────────┼─────────┼───────────────┼──────────────►│
    │◄─── auth code ───────────┼─────────┼───────────────┼───────────────│
    │                          │         │               │               │
    │                          │◄─ Cognito token ────────│               │
    │◄─ session cookie ────────│                         │               │
    │                          │                         │               │
    ├─ MCP request + cookie ──►│── forward ─────────────►│               │
    │◄─ MCP response ─────────│◄── response ────────────│               │
```

**Important**: This flow uses ALB's built-in Cognito auth action, which sets a session cookie after Google login. The MCP app receives requests only after authentication succeeds at the ALB layer. No app code changes needed.

However, Claude's connector expects MCP OAuth spec endpoints (`/.well-known/oauth-*`, `/authorize`, `/token`, `/register`), not ALB cookie-based auth. There are two sub-options:

**Option B1: ALB + Cognito (cookie auth)** — Works if Claude supports cookie-based sessions for connectors. Simpler, no app changes.

**Option B2: Cognito as OAuth server + app validates tokens** — The app exposes MCP OAuth discovery endpoints that point to Cognito's OAuth endpoints. Claude does the OAuth flow with Cognito (Google-federated). The app validates the Cognito JWT on each request. Requires a thin security filter in the app, but no full authorization server.

Option B2 is more likely to work with Claude connectors. Detailed below.

### 1. ECR Repository

```bash
REGION=ap-south-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/broker-mcp"

aws ecr create-repository --repository-name broker-mcp --region $REGION

aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"
docker tag pavithravasudevan/broker-mcp:latest "$ECR_URI:latest"
docker push "$ECR_URI:latest"
```

### 2. Secrets Manager (broker session tokens)

```bash
aws secretsmanager create-secret \
  --name broker-mcp/session \
  --description "Broker MCP session tokens, updated daily by local cron" \
  --secret-string '{"BREEZE_ENABLED":"false","ZERODHA_ENABLED":"false"}' \
  --region $REGION
```

### 3. Cognito User Pool + Google Federation

```bash
# Create user pool
POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name broker-mcp \
  --auto-verified-attributes email \
  --mfa-configuration OFF \
  --region $REGION \
  --query "UserPool.Id" --output text)

# Create domain for hosted UI
aws cognito-idp create-user-pool-domain \
  --domain broker-mcp-auth \
  --user-pool-id $POOL_ID \
  --region $REGION

# Add Google as identity provider
aws cognito-idp create-identity-provider \
  --user-pool-id $POOL_ID \
  --provider-name Google \
  --provider-type Google \
  --provider-details '{
    "client_id": "YOUR_GOOGLE_CLIENT_ID",
    "client_secret": "YOUR_GOOGLE_CLIENT_SECRET",
    "authorize_scopes": "openid email profile"
  }' \
  --attribute-mapping '{"email": "email", "username": "sub"}' \
  --region $REGION

# Create app client (for Claude connector)
CLIENT_ID=$(aws cognito-idp create-user-pool-client \
  --user-pool-id $POOL_ID \
  --client-name broker-mcp-claude \
  --generate-secret \
  --supported-identity-providers Google \
  --callback-urls '["https://claude.ai/api/mcp/auth_callback"]' \
  --allowed-o-auth-flows code \
  --allowed-o-auth-scopes "openid email" \
  --allowed-o-auth-flows-user-pool-client \
  --region $REGION \
  --query "UserPoolClient.ClientId" --output text)
```

Google OAuth Credentials (same as VPS track):
1. Google Cloud Console > APIs & Services > Credentials
2. Create OAuth 2.0 Client ID (Web application)
3. Authorized redirect URI: `https://broker-mcp-auth.auth.ap-south-1.amazoncognito.com/oauth2/idpresponse`

### Cognito Cost Safety Checklist

- [ ] Use **Essentials** tier (not Plus)
- [ ] Do NOT enable Advanced Security features
- [ ] One user pool only
- [ ] Delete old unused user pools: `aws cognito-idp list-user-pools --max-results 20 --region ap-south-1`
- [ ] 1 MAU with Google (OIDC) federation = within 50 free OIDC MAUs = **$0**

### 4. IAM — Task Execution Role

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
```

```bash
aws iam create-role \
  --role-name broker-mcp-execution \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

aws iam put-role-policy \
  --role-name broker-mcp-execution \
  --policy-name broker-mcp-policy \
  --policy-document file://ecs-execution-policy.json
```

### 5. IAM — Local Cron User

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:PutSecretValue",
      "Resource": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session-*"
    },
    {
      "Effect": "Allow",
      "Action": "ecs:UpdateService",
      "Resource": "arn:aws:ecs:ap-south-1:ACCOUNT_ID:service/broker-mcp/broker-mcp"
    }
  ]
}
```

### 6. VPC + Networking

```bash
VPC_ID=$(aws ec2 describe-vpcs --filters "Name=isDefault,Values=true" --query "Vpcs[0].VpcId" --output text --region $REGION)

SUBNETS=$(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC_ID" --query "Subnets[?MapPublicIpOnLaunch].SubnetId" --output text --region $REGION)

# ALB security group
ALB_SG=$(aws ec2 create-security-group --group-name broker-mcp-alb --description "ALB for broker-mcp" --vpc-id $VPC_ID --region $REGION --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $ALB_SG --protocol tcp --port 443 --cidr 0.0.0.0/0 --region $REGION

# ECS task security group (only from ALB)
ECS_SG=$(aws ec2 create-security-group --group-name broker-mcp-ecs --description "ECS tasks" --vpc-id $VPC_ID --region $REGION --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $ECS_SG --protocol tcp --port 8081 --source-group $ALB_SG --region $REGION
```

### 7. ALB + ACM + Cognito Auth

```bash
# ACM certificate
CERT_ARN=$(aws acm request-certificate \
  --domain-name mcp.yourdomain.com \
  --validation-method DNS \
  --region $REGION \
  --query CertificateArn --output text)

# Create ALB
ALB_ARN=$(aws elbv2 create-load-balancer \
  --name broker-mcp-alb \
  --subnets $SUBNETS \
  --security-groups $ALB_SG \
  --scheme internet-facing \
  --type application \
  --region $REGION \
  --query "LoadBalancers[0].LoadBalancerArn" --output text)

# Target group
TG_ARN=$(aws elbv2 create-target-group \
  --name broker-mcp-tg \
  --protocol HTTP \
  --port 8081 \
  --vpc-id $VPC_ID \
  --target-type ip \
  --health-check-path /mcp \
  --health-check-interval-seconds 30 \
  --region $REGION \
  --query "TargetGroups[0].TargetGroupArn" --output text)

# HTTPS listener with Cognito authenticate action
# The listener first authenticates via Cognito, then forwards to the target
aws elbv2 create-listener \
  --load-balancer-arn $ALB_ARN \
  --protocol HTTPS \
  --port 443 \
  --certificates CertificateArn=$CERT_ARN \
  --default-actions '[
    {
      "Type": "authenticate-cognito",
      "Order": 1,
      "AuthenticateCognitoConfig": {
        "UserPoolArn": "arn:aws:cognito-idp:ap-south-1:ACCOUNT_ID:userpool/POOL_ID",
        "UserPoolClientId": "CLIENT_ID",
        "UserPoolDomain": "broker-mcp-auth",
        "OnUnauthenticatedRequest": "authenticate"
      }
    },
    {
      "Type": "forward",
      "Order": 2,
      "TargetGroupArn": "'$TG_ARN'"
    }
  ]' \
  --region $REGION
```

Note on MCP connector compatibility: If Claude's connector does not follow ALB's cookie-based auth redirect, you'll need the app to expose MCP OAuth discovery endpoints that point to Cognito's OAuth URLs. This is a thin adapter — no full authorization server needed, just:
- `/.well-known/oauth-protected-resource` → points to Cognito
- `/.well-known/oauth-authorization-server` → returns Cognito's metadata
- Token validation via Cognito JWKS

### 8. ECS Cluster + Task Definition

```bash
aws ecs create-cluster --cluster-name broker-mcp --region $REGION
aws logs create-log-group --log-group-name /ecs/broker-mcp --region $REGION
```

Task definition (`broker-mcp-task.json`):

```json
{
  "family": "broker-mcp",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::ACCOUNT_ID:role/broker-mcp-execution",
  "containerDefinitions": [
    {
      "name": "broker-mcp",
      "image": "ACCOUNT_ID.dkr.ecr.ap-south-1.amazonaws.com/broker-mcp:latest",
      "portMappings": [
        {"containerPort": 8081, "protocol": "tcp"}
      ],
      "environment": [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "http,docker"}
      ],
      "secrets": [
        {"name": "BREEZE_ENABLED", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:BREEZE_ENABLED::"},
        {"name": "BREEZE_API_KEY", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:BREEZE_API_KEY::"},
        {"name": "BREEZE_SECRET", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:BREEZE_SECRET::"},
        {"name": "BREEZE_SESSION", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:BREEZE_SESSION::"},
        {"name": "ZERODHA_ENABLED", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:ZERODHA_ENABLED::"},
        {"name": "ZERODHA_API_KEY", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:ZERODHA_API_KEY::"},
        {"name": "ZERODHA_ACCESS_TOKEN", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:ZERODHA_ACCESS_TOKEN::"},
        {"name": "ZERODHA_USER_ID", "valueFrom": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session:ZERODHA_USER_ID::"}
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/broker-mcp",
          "awslogs-region": "ap-south-1",
          "awslogs-stream-prefix": "ecs"
        }
      },
      "mountPoints": [
        {"sourceVolume": "broker-data", "containerPath": "/data"}
      ]
    }
  ],
  "volumes": [
    {
      "name": "broker-data",
      "efsVolumeConfiguration": {
        "fileSystemId": "EFS_ID",
        "rootDirectory": "/broker-mcp"
      }
    }
  ]
}
```

EFS is needed for persistent `/data` (tradebook, corporate actions). If you don't need persistence across restarts, remove the volume config.

```bash
aws ecs register-task-definition --cli-input-json file://broker-mcp-task.json --region $REGION
```

### 9. ECS Service

```bash
aws ecs create-service \
  --cluster broker-mcp \
  --service-name broker-mcp \
  --task-definition broker-mcp \
  --desired-count 1 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$ECS_SG],assignPublicIp=ENABLED}" \
  --load-balancers "targetGroupArn=$TG_ARN,containerName=broker-mcp,containerPort=8081" \
  --region $REGION
```

### 10. Claude Connector Setup

1. Go to claude.ai > Settings > Connectors
2. Click "Add custom connector"
3. Enter URL: `https://mcp.yourdomain.com/mcp`
4. If using Cognito as MCP OAuth server (Option B2):
   - In Advanced settings, provide the Cognito app client ID and secret
5. OAuth flow redirects to Google login → you authorize
6. Connector syncs to Claude mobile automatically

### ECS Cost Estimate

| Resource | Monthly |
|----------|---------|
| Fargate (0.5 vCPU, 1GB, 24/7) | ~$15 |
| ALB | ~$16 + $0.008/LCU-hr |
| Cognito (1 OIDC MAU, Essentials) | $0 |
| Secrets Manager (1 secret) | ~$0.40 |
| ACM certificate | Free |
| EFS (if used) | ~$0.30/GB |
| **Total** | **~$32-35/mo** |

Cheaper than the WAF approach (~$5 saved by dropping WAF in favor of Cognito).

### ECS Security Summary

| Layer | What |
|-------|------|
| Network | ALB SG: 443 only. ECS SG: 8081 from ALB only |
| Transport | TLS via ACM (auto-renew) |
| Auth | Cognito OAuth 2.1 + Google federation |
| Identity | Cognito user pool — only your Google account is registered |
| Secrets | Secrets Manager, injected at task start. IAM scoped to single secret |
| IAM | Execution role: least-privilege. Local cron user: put-secret + update-service only |
| App | `readonly` mode by default |
| Logging | CloudWatch Logs for audit trail |

---

## Track C: EKS Deployment

Kubernetes on AWS. Most complex, best for learning K8s patterns. Same Cognito + Google auth as ECS.

### Architecture

```
                        ┌──────────────────────────────────────┐
                        │             AWS Account               │
                        │                                       │
  Internet ──► ALB (443)│──► Cognito auth ──► Ingress ──► Pod   │
                │       │       │                     │         │
                │       │  AWS LB Controller     broker-mcp    │
                │       │  + ACM cert            container     │
                │       │                            │         │
                │       │                    K8s Secret         │
                │       │                      ▲               │
                │       │              ExternalSecrets          │
                │       │                      ▲               │
                │       │              Secrets Manager          │
                │       │                                       │
                │       │              Reloader                 │
                │       │  (watches secret, restarts pods)     │
                └───────┴──────────────────────────────────────┘
```

### 1. EKS Cluster

```bash
eksctl create cluster \
  --name broker-mcp \
  --region ap-south-1 \
  --version 1.31 \
  --nodegroup-name workers \
  --node-type t3.small \
  --nodes 1 \
  --nodes-min 1 \
  --nodes-max 2 \
  --managed
```

Or use Fargate profiles to avoid managing nodes:

```bash
eksctl create cluster \
  --name broker-mcp \
  --region ap-south-1 \
  --version 1.31 \
  --fargate
```

### 2. Cognito Setup

Same as ECS Track (step 3). Reuse the same user pool, Google IdP, and app client.

### 3. AWS Load Balancer Controller

```bash
helm repo add eks https://aws.github.io/eks-charts
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=broker-mcp \
  --set serviceAccount.create=true \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=arn:aws:iam::ACCOUNT_ID:role/aws-lb-controller
```

IRSA setup required — see [AWS docs](https://docs.aws.amazon.com/eks/latest/userguide/aws-load-balancer-controller.html).

### 4. ExternalSecrets Operator

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace
```

IRSA for ExternalSecrets:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": "arn:aws:secretsmanager:ap-south-1:ACCOUNT_ID:secret:broker-mcp/session-*"
    }
  ]
}
```

### 5. Stakater Reloader

```bash
helm repo add stakater https://stakater.github.io/stakater-charts
helm install reloader stakater/reloader -n reloader --create-namespace
```

### 6. Kubernetes Manifests

```yaml
# namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: broker-mcp
```

```yaml
# service-account.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: broker-mcp-sa
  namespace: broker-mcp
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::ACCOUNT_ID:role/broker-mcp-eks-role
```

```yaml
# secret-store.yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata:
  name: aws-secrets
  namespace: broker-mcp
spec:
  provider:
    aws:
      service: SecretsManager
      region: ap-south-1
      auth:
        jwt:
          serviceAccountRef:
            name: broker-mcp-sa
```

```yaml
# external-secret.yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: broker-session
  namespace: broker-mcp
spec:
  refreshInterval: 1m
  secretStoreRef:
    name: aws-secrets
    kind: SecretStore
  target:
    name: broker-session
    creationPolicy: Owner
  dataFrom:
    - extract:
        key: broker-mcp/session
```

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: broker-mcp
  namespace: broker-mcp
  annotations:
    reloader.stakater.com/auto: "true"
spec:
  replicas: 1
  selector:
    matchLabels:
      app: broker-mcp
  template:
    metadata:
      labels:
        app: broker-mcp
    spec:
      serviceAccountName: broker-mcp-sa
      containers:
        - name: broker-mcp
          image: ACCOUNT_ID.dkr.ecr.ap-south-1.amazonaws.com/broker-mcp:latest
          ports:
            - containerPort: 8081
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "http,docker"
          envFrom:
            - secretRef:
                name: broker-session
          volumeMounts:
            - name: data
              mountPath: /data
          resources:
            requests:
              cpu: 256m
              memory: 512Mi
            limits:
              cpu: 512m
              memory: 1Gi
          readinessProbe:
            httpGet:
              path: /mcp
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /mcp
              port: 8081
            initialDelaySeconds: 60
            periodSeconds: 30
      volumes:
        - name: data
          emptyDir: {}
```

```yaml
# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: broker-mcp
  namespace: broker-mcp
spec:
  selector:
    app: broker-mcp
  ports:
    - port: 8081
      targetPort: 8081
      protocol: TCP
  type: ClusterIP
```

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: broker-mcp
  namespace: broker-mcp
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
    alb.ingress.kubernetes.io/certificate-arn: ACM_CERT_ARN
    alb.ingress.kubernetes.io/ssl-policy: ELBSecurityPolicy-TLS13-1-2-2021-06
    alb.ingress.kubernetes.io/healthcheck-path: /mcp
    # Cognito authentication
    alb.ingress.kubernetes.io/auth-type: cognito
    alb.ingress.kubernetes.io/auth-idp-cognito: '{"userPoolARN":"arn:aws:cognito-idp:ap-south-1:ACCOUNT_ID:userpool/POOL_ID","userPoolClientID":"CLIENT_ID","userPoolDomain":"broker-mcp-auth"}'
    alb.ingress.kubernetes.io/auth-on-unauthenticated-request: authenticate
spec:
  rules:
    - host: mcp.yourdomain.com
      http:
        paths:
          - path: /mcp
            pathType: Prefix
            backend:
              service:
                name: broker-mcp
                port:
                  number: 8081
```

### 7. Deploy

```bash
kubectl apply -f namespace.yaml
kubectl apply -f service-account.yaml
kubectl apply -f secret-store.yaml
kubectl apply -f external-secret.yaml
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
kubectl apply -f ingress.yaml
```

### 8. Token Refresh Flow (automatic)

1. Local cron runs `push-session-aws.sh` → updates Secrets Manager
2. ExternalSecrets polls every 1m → detects change → updates K8s Secret `broker-session`
3. Reloader detects Secret change → rolling restart of `broker-mcp` pods
4. New pod starts with fresh tokens

No manual `kubectl rollout restart` needed.

### 9. Claude Connector Setup

Same as ECS track — connectors are account-level, not infrastructure-specific.

### EKS Cost Estimate

| Resource | Monthly |
|----------|---------|
| EKS control plane | $73 |
| t3.small node (1x) | ~$15 |
| ALB | ~$16 + LCU |
| Cognito (1 OIDC MAU, Essentials) | $0 |
| Secrets Manager | ~$0.40 |
| ACM | Free |
| **Total** | **~$105/mo** |

With Fargate profiles instead of managed nodes: ~$88/mo.

### EKS Security Summary

| Layer | What |
|-------|------|
| Network | ALB SG: 443 only. Pod SG: 8081 from ALB. NetworkPolicy optional |
| Transport | TLS via ACM, TLS 1.3 policy |
| Auth | Cognito OAuth 2.1 + Google federation (same as ECS) |
| Identity | Cognito user pool — only your Google account |
| Secrets | Secrets Manager → ExternalSecrets → K8s Secret. IRSA for least-privilege |
| IAM | IRSA per service account. No shared node-level credentials |
| App | `readonly` mode, resource limits set |
| Observability | CloudWatch Container Insights, pod-level logging |

---

## Comparison

| | VPS | ECS Fargate | EKS |
|---|---|---|---|
| **Cost** | $5-10/mo | $32-35/mo | $88-105/mo |
| **Ops effort** | Medium (you patch OS + manage OAuth code) | Low (AWS manages infra + auth) | High (K8s complexity) |
| **Setup time** | ~2-3 hours (includes OAuth code) | ~2-3 hours | ~4-6 hours |
| **Auth approach** | Spring Security OAuth2 + Google | Cognito + Google (ALB-native) | Cognito + Google (ALB Ingress) |
| **App code changes** | Yes (Spring Security + OAuth endpoints) | None (or thin adapter) | None (or thin adapter) |
| **Secret rotation** | scp + ssh restart | Secrets Manager + force-deploy | Secrets Manager + ExternalSecrets + Reloader |
| **TLS** | Let's Encrypt + certbot | ACM (auto-renew) | ACM (auto-renew) |
| **Scaling** | Manual | `desired-count` | HPA / replicas |
| **Learning value** | Linux, nginx, Spring Security OAuth2 | AWS services, Cognito, IAM | Kubernetes, Helm, IRSA, ExternalSecrets |
| **Best for** | Production (cheapest) | Production (hands-off) | Learning K8s |

---

## Open Questions

1. **Domain**: Do you have one, or need to register?
2. **Claude connector + Cognito compatibility**: Need to verify that Claude's connector OAuth flow works with Cognito's OAuth endpoints. If Cognito doesn't support Dynamic Client Registration (DCR) as required by the MCP spec, we may need a thin proxy layer. To be validated during implementation.
3. **Persistence**: Do you need tradebook/corporate-action data to survive container restarts? If yes, EFS is needed for ECS/EKS tracks.
4. **Which track to implement first?**
