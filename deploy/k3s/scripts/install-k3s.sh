#!/usr/bin/env sh
set -eu

if [ "$(uname -s)" != "Linux" ]; then
  echo "k3s requires Linux. Run this inside an Ubuntu/Amazon Linux server or VM." >&2
  exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root: sudo $0" >&2
  exit 1
fi

INSTALL_K3S_CHANNEL="${INSTALL_K3S_CHANNEL:-stable}"
K3S_EXEC="${K3S_EXEC:-server --disable=traefik --write-kubeconfig-mode=644}"

mkdir -p \
  /var/lib/buddystudy/mysql \
  /var/lib/buddystudy/redis/standalone \
  /var/lib/buddystudy/libretranslate \
  /var/lib/buddystudy/backups/mysql

curl -sfL https://get.k3s.io | INSTALL_K3S_CHANNEL="$INSTALL_K3S_CHANNEL" K3S_EXEC="$K3S_EXEC" sh -

systemctl enable k3s
systemctl restart k3s

echo "k3s installed"
echo "kubeconfig: /etc/rancher/k3s/k3s.yaml"
