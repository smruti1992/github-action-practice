# End-to-End CI/CD Pipeline — DevOps Practice

A production-like CI/CD pipeline for a Maven-based Java web application.  
Uses **GitHub Actions → SonarQube → JFrog Artifactory → Ansible → Apache Tomcat**.

---

## Table of Contents

1. [Project Structure](#project-structure)
2. [Pipeline Overview](#pipeline-overview)
3. [Prerequisites](#prerequisites)
4. [Infrastructure Setup](#infrastructure-setup)
5. [GitHub Secrets](#github-secrets)
6. [GitHub Environments](#github-environments)
7. [Running the Pipeline](#running-the-pipeline)
8. [Multi-Environment Support](#multi-environment-support)
9. [Rollback Mechanism](#rollback-mechanism)
10. [Notifications (Slack)](#notifications-slack)
11. [Local Development](#local-development)

---

## Project Structure

```
.
├── .github/
│   └── workflows/
│       └── cicd-pipeline.yml    ← Main CI/CD workflow
├── ansible/
│   ├── deploy.yml               ← Deployment playbook
│   ├── rollback.yml             ← Rollback playbook
│   ├── inventory/
│   │   ├── dev/hosts
│   │   ├── staging/hosts
│   │   └── prod/hosts
│   └── roles/
│       ├── tomcat-deploy/       ← Deploy WAR to Tomcat
│       └── tomcat-rollback/     ← Restore previous WAR
├── src/
│   ├── main/java/com/example/webapp/HelloServlet.java
│   ├── main/webapp/WEB-INF/web.xml
│   ├── main/webapp/index.jsp
│   └── test/java/com/example/webapp/HelloServletTest.java
├── pom.xml
├── sonar-project.properties
└── README.md
```

---

## Pipeline Overview

```
Push to main/develop/release/*
         │
         ▼
┌─────────────────┐
│   1. Build      │  mvn clean verify  →  WAR artifact
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  2. SonarQube   │  Static analysis + Quality Gate (fails pipeline on violation)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  3. Publish     │  Upload WAR to JFrog Artifactory (main/release only)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  4. Deploy Dev  │  Auto-deploy via Ansible
└────────┬────────┘
         │  (manual approval)
         ▼
┌──────────────────┐
│ 5. Deploy Staging│  Ansible deploy after reviewer approval
└────────┬─────────┘
         │  (manual approval)
         ▼
┌──────────────────┐
│  6. Deploy Prod  │  Ansible deploy after reviewer approval
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  7. Notify Slack │  Success/failure notification
└──────────────────┘
```

Each deploy stage has an **automatic rollback** step that triggers if the deployment fails.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java (JDK) | 11 | Temurin recommended |
| Maven | 3.8+ | |
| Docker | 20+ | For running SonarQube & Artifactory locally |
| Ansible | 2.14+ | Installed on the GitHub Actions runner |
| Apache Tomcat | 9 or 10 | On target servers |

---

## Infrastructure Setup

### SonarQube

**Option A — Docker (recommended for learning)**

```bash
docker run -d \
  --name sonarqube \
  -p 9000:9000 \
  -e SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true \
  sonarqube:community

# Open http://<server-ip>:9000
# Default credentials: admin / admin  (change immediately)
```

**After first login:**

1. Go to **Administration → Security → Users** → generate a token for the CI user.
2. Go to **Administration → Projects** → create a project with key `webapp`.
3. Optionally configure a Quality Gate under **Quality Gates**.

**Option B — Bare-metal (Ubuntu 22.04)**

```bash
# Install PostgreSQL
sudo apt update && sudo apt install -y openjdk-17-jre-headless postgresql postgresql-contrib
sudo -u postgres psql -c "CREATE USER sonar WITH ENCRYPTED PASSWORD 'sonar';"
sudo -u postgres psql -c "CREATE DATABASE sonarqube OWNER sonar;"

# Download and install SonarQube
SONAR_VERSION=10.4.1.88267
wget "https://binaries.sonarsource.com/Distribution/sonarqube/sonarqube-${SONAR_VERSION}.zip"
unzip "sonarqube-${SONAR_VERSION}.zip" -d /opt
sudo ln -s "/opt/sonarqube-${SONAR_VERSION}" /opt/sonarqube
sudo useradd --system --no-create-home --shell /bin/false sonar
sudo chown -R sonar:sonar /opt/sonarqube

# Configure DB connection in sonar.properties
sudo sed -i \
  -e 's|#sonar.jdbc.username=|sonar.jdbc.username=sonar|' \
  -e 's|#sonar.jdbc.password=|sonar.jdbc.password=sonar|' \
  -e 's|#sonar.jdbc.url=jdbc:postgresql.*|sonar.jdbc.url=jdbc:postgresql://localhost/sonarqube|' \
  /opt/sonarqube/conf/sonar.properties

# Systemd service
sudo tee /etc/systemd/system/sonarqube.service > /dev/null <<'EOF'
[Unit]
Description=SonarQube service
After=network.target

[Service]
Type=forking
ExecStart=/opt/sonarqube/bin/linux-x86-64/sonar.sh start
ExecStop=/opt/sonarqube/bin/linux-x86-64/sonar.sh stop
User=sonar
Group=sonar
Restart=always
LimitNOFILE=65536
LimitNPROC=4096

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload && sudo systemctl enable --now sonarqube
```

---

### JFrog Artifactory

**Option A — Docker**

```bash
docker run -d \
  --name artifactory \
  -p 8081:8081 -p 8082:8082 \
  -v artifactory-data:/var/opt/jfrog/artifactory \
  releases-docker.jfrog.io/jfrog/artifactory-oss:latest

# Open http://<server-ip>:8082
# Default credentials: admin / password  (change immediately)
```

**After first login:**

1. Create a **local Maven repository** named `libs-release-local`.
2. Create a CI service account under **Administration → Identity and Access → Users**.
3. Generate an **API Key** or **Access Token** for that user.

**Option B — Bare-metal (Ubuntu 22.04)**

```bash
wget -qO - https://releases.jfrog.io/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://releases.jfrog.io/artifactory/artifactory-debs xenial main" \
  | sudo tee /etc/apt/sources.list.d/jfrog.list
sudo apt update && sudo apt install -y jfrog-artifactory-oss
sudo systemctl enable --now artifactory
```

---

### Target Server (Tomcat)

Run on each target VM (dev / staging / prod):

```bash
sudo apt update && sudo apt install -y openjdk-11-jre-headless
sudo useradd --system --no-create-home --shell /bin/false tomcat
sudo mkdir -p /opt/tomcat /opt/tomcat/war-backups

TOMCAT_VERSION=10.1.20
wget "https://archive.apache.org/dist/tomcat/tomcat-10/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz"
sudo tar xzf "apache-tomcat-${TOMCAT_VERSION}.tar.gz" -C /opt/tomcat --strip-components=1
sudo chown -R tomcat:tomcat /opt/tomcat

sudo tee /etc/systemd/system/tomcat.service > /dev/null <<'EOF'
[Unit]
Description=Apache Tomcat Web Application Server
After=network.target

[Service]
Type=forking
User=tomcat
Group=tomcat
Environment="JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64"
Environment="CATALINA_HOME=/opt/tomcat"
Environment="CATALINA_PID=/opt/tomcat/temp/tomcat.pid"
ExecStart=/opt/tomcat/bin/startup.sh
ExecStop=/opt/tomcat/bin/shutdown.sh
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload && sudo systemctl enable --now tomcat
curl -s http://localhost:8080 | grep -o "Apache Tomcat"
```

---

### SSH Access for Ansible

```bash
# Generate a dedicated CI deploy key (no passphrase)
ssh-keygen -t ed25519 -f ~/.ssh/ci_deploy_key -N "" -C "github-actions-deploy"

# Copy public key to each target server
for SERVER in <DEV_IP> <STAGING_IP> <PROD_IP>; do
  ssh-copy-id -i ~/.ssh/ci_deploy_key.pub <DEPLOY_USER>@"${SERVER}"
done

# Test connectivity
ssh -i ~/.ssh/ci_deploy_key <DEPLOY_USER>@<DEV_IP> "echo OK"
```

Copy the private key (`~/.ssh/ci_deploy_key`) and add it as the `SSH_PRIVATE_KEY` GitHub Secret.

---

## GitHub Secrets

**Settings → Secrets and variables → Actions**

| Secret | Description | Example |
|--------|-------------|---------|
| `SONAR_HOST_URL` | SonarQube base URL | `http://sonarqube.example.com:9000` |
| `SONAR_TOKEN` | SonarQube user token | `squ_abc123...` |
| `JFROG_URL` | Artifactory base URL | `https://myorg.jfrog.io/myorg` |
| `JFROG_USERNAME` | Artifactory username | `ci-user` |
| `JFROG_PASSWORD` | Artifactory API key | `AKCp...` |
| `SSH_PRIVATE_KEY` | ED25519 private key (PEM) | `-----BEGIN OPENSSH PRIVATE KEY-----...` |
| `DEPLOY_USER` | OS user on target servers | `ubuntu` |
| `DEV_SERVER_IP` | Dev server hostname or IP | `10.0.1.10` |
| `STAGING_SERVER_IP` | Staging server hostname or IP | `10.0.1.20` |
| `PROD_SERVER_IP` | Prod server hostname or IP | `10.0.1.30` |
| `SLACK_WEBHOOK_URL` | Slack incoming webhook (optional) | `https://hooks.slack.com/...` |

---

## GitHub Environments

**Settings → Environments** — create three environments:

| Environment | Protection rules |
|-------------|-----------------|
| `dev` | None (auto-deploy) |
| `staging` | Required reviewers (1+) |
| `prod` | Required reviewers (2+), optional wait timer |

---

## Running the Pipeline

The pipeline triggers automatically on:

- **Push** to `main`, `develop`, or `release/**` branches.
- **Pull request** targeting `main` or `develop` (build + SonarQube only; no deployment).

Manual trigger: **Actions → CI/CD Pipeline → Run workflow**.

---

## Multi-Environment Support

| Branch | Build | Sonar | Publish | Dev | Staging | Prod |
|--------|-------|-------|---------|-----|---------|------|
| PR | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| `develop` | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| `main` / `release/**` | ✅ | ✅ | ✅ | ✅ | ✅ (approval) | ✅ (approval) |

---

## Rollback Mechanism

Each deploy stage includes a `Rollback on failure` step.  
The rollback playbook (`ansible/rollback.yml`):

1. Finds the most recent `.war` in `/opt/tomcat/war-backups/`.
2. Stops Tomcat.
3. Restores the backup WAR to `webapps/`.
4. Restarts Tomcat and waits for port 8080.
5. Appends an entry to `/opt/tomcat/war-backups/rollback.log`.

**Manual rollback:**

```bash
ansible-playbook ansible/rollback.yml \
  --inventory <DEV_IP>, \
  --user <DEPLOY_USER> \
  --private-key ~/.ssh/ci_deploy_key \
  --extra-vars "env_name=dev"
```

---

## Notifications (Slack)

1. Create a [Slack App](https://api.slack.com/apps) with **Incoming Webhooks** enabled.
2. Add the webhook URL as the `SLACK_WEBHOOK_URL` GitHub Secret.

The `notify` job sends a colour-coded message after all deploy jobs finish:  
🟢 green = all passed · 🔴 red = at least one failure · 🟡 yellow = skipped.

---

## Local Development

```bash
# Build and test
mvn clean verify

# SonarQube scan
mvn sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=<your-token>

# Generated WAR
ls -lh target/*.war

# Manual Ansible deploy
ansible-playbook ansible/deploy.yml \
  --inventory ansible/inventory/dev/hosts \
  --extra-vars "app_war_name=webapp-1.0.0-local.war \
                app_version=1.0.0-local \
                jfrog_url=http://localhost:8082 \
                jfrog_username=admin \
                jfrog_password=<password> \
                env_name=dev"
```
