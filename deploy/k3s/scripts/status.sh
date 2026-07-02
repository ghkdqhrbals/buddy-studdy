#!/usr/bin/env sh
set -eu

KUBECTL="${KUBECTL:-kubectl}"

"$KUBECTL" -n buddystudy get pods,svc,pvc,pv -o wide
echo
for pod in buddystudy-redis-0 buddystudy-redis-1 buddystudy-redis-2; do
  echo "--- $pod"
  "$KUBECTL" -n buddystudy exec "$pod" -- sh -c 'redis-cli -a "$REDIS_PASSWORD" cluster info | grep -E "cluster_state|cluster_slots_ok|cluster_slots_pfail|cluster_slots_fail"'
done
