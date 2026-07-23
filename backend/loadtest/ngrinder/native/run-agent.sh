#!/usr/bin/env bash
set -euo pipefail

controller="${1:-controller:80}"
agent_archive="/tmp/ngrinder-agent.tar"
mkdir -p "$NGRINDER_AGENT_BASE"

if [[ ! -x "$NGRINDER_AGENT_BASE/run_agent.sh" ]]; then
  for _ in $(seq 1 60); do
    if curl -fsSL "http://$controller/agent/download" -o "$agent_archive"; then
      break
    fi
    sleep 2
  done
  if [[ ! -s "$agent_archive" ]]; then
    echo "Unable to download nGrinder agent from $controller" >&2
    exit 1
  fi
  tar -xf "$agent_archive" -C /opt
fi

exec "$NGRINDER_AGENT_BASE/run_agent.sh"
