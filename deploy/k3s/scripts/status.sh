#!/usr/bin/env sh
set -eu

KUBECTL="${KUBECTL:-kubectl}"

"$KUBECTL" -n buddystudy get pods,svc,pvc,pv -o wide
echo
echo "--- buddystudy-redis-0"
"$KUBECTL" -n buddystudy exec buddystudy-redis-0 -- \
  sh -c 'redis-cli -a "$REDIS_PASSWORD" ping'
