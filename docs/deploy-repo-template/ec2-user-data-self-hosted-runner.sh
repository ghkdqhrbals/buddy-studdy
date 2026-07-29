#!/usr/bin/env bash
set -euo pipefail

# EC2 user-data template for the BuddyStudy deploy runner.
#
# Required replacements before launching the instance:
#   __GITHUB_OWNER__  GitHub owner, for example ghkdqhrbals
#   __GITHUB_REPO__   deploy repository name, for example personal-deploy
#   __GITHUB_PAT__    PAT that can create repository self-hosted runner registration tokens
#
# The runner is installed once and then managed by systemd. Reboots restart the
# runner automatically; image builds must still happen on GitHub-hosted runners.

GITHUB_OWNER="__GITHUB_OWNER__"
GITHUB_REPO="__GITHUB_REPO__"
GITHUB_PAT="__GITHUB_PAT__"
RUNNER_NAME="${RUNNER_NAME:-buddystudy-ec2-$(hostname)}"
RUNNER_LABELS="${RUNNER_LABELS:-ec2,buddystudy,linux,arm64}"
RUNNER_VERSION="${RUNNER_VERSION:-2.327.1}"
RUNNER_USER="${RUNNER_USER:-actions-runner}"
RUNNER_HOME="/opt/actions-runner"

if [ "${GITHUB_OWNER}" = "__GITHUB_OWNER__" ] || [ "${GITHUB_REPO}" = "__GITHUB_REPO__" ] || [ "${GITHUB_PAT}" = "__GITHUB_PAT__" ]; then
  echo "Replace __GITHUB_OWNER__, __GITHUB_REPO__, and __GITHUB_PAT__ before using this user-data script." >&2
  exit 1
fi

install_packages() {
  if command -v dnf >/dev/null 2>&1; then
    dnf update -y
    dnf install -y docker git jq tar gzip shadow-utils awscli libicu lttng-ust krb5-libs zlib openssl-libs
  elif command -v yum >/dev/null 2>&1; then
    yum update -y
    yum install -y docker git jq tar gzip shadow-utils awscli libicu lttng-ust krb5-libs zlib openssl-libs
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get update
    apt-get install -y docker.io git jq tar gzip curl awscli
  else
    echo "Unsupported Linux distribution: no dnf, yum, or apt-get." >&2
    exit 1
  fi
}

install_packages

systemctl enable --now docker

if ! id "${RUNNER_USER}" >/dev/null 2>&1; then
  useradd --system --create-home --shell /bin/bash "${RUNNER_USER}"
fi
usermod -aG docker "${RUNNER_USER}"

mkdir -p "${RUNNER_HOME}"
chown "${RUNNER_USER}:${RUNNER_USER}" "${RUNNER_HOME}"

if [ ! -x "${RUNNER_HOME}/run.sh" ]; then
  arch="$(uname -m)"
  case "${arch}" in
    aarch64|arm64) runner_arch="arm64" ;;
    x86_64|amd64) runner_arch="x64" ;;
    *) echo "Unsupported runner architecture: ${arch}" >&2; exit 1 ;;
  esac

  runner_url="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-${runner_arch}-${RUNNER_VERSION}.tar.gz"
  tmp_tar="/tmp/actions-runner.tar.gz"
  curl -fsSL "${runner_url}" -o "${tmp_tar}"
  tar -xzf "${tmp_tar}" -C "${RUNNER_HOME}"
  chown -R "${RUNNER_USER}:${RUNNER_USER}" "${RUNNER_HOME}"
fi

registration_token="$(
  curl -fsSL \
    -X POST \
    -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer ${GITHUB_PAT}" \
    "https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/actions/runners/registration-token" \
    | jq -r '.token'
)"

if [ -z "${registration_token}" ] || [ "${registration_token}" = "null" ]; then
  echo "Could not create GitHub Actions runner registration token." >&2
  exit 1
fi

if [ ! -f "${RUNNER_HOME}/.runner" ]; then
  sudo -u "${RUNNER_USER}" bash -lc "cd '${RUNNER_HOME}' && ./config.sh \
    --url 'https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}' \
    --token '${registration_token}' \
    --name '${RUNNER_NAME}' \
    --labels '${RUNNER_LABELS}' \
    --work '_work' \
    --unattended \
    --replace"
fi

cat > /etc/systemd/system/buddystudy-github-runner.service <<SYSTEMD
[Unit]
Description=BuddyStudy GitHub Actions self-hosted runner
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
User=${RUNNER_USER}
WorkingDirectory=${RUNNER_HOME}
ExecStart=${RUNNER_HOME}/run.sh
Restart=always
RestartSec=10
KillSignal=SIGINT
TimeoutStopSec=60
Environment=RUNNER_ALLOW_RUNASROOT=0

[Install]
WantedBy=multi-user.target
SYSTEMD

systemctl daemon-reload
systemctl enable --now buddystudy-github-runner.service

echo "BuddyStudy EC2 GitHub runner installed and started."
