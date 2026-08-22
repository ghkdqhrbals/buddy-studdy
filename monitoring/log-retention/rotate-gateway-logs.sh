#!/bin/sh

set -eu

log_directory=${LOG_DIRECTORY:-/var/log/nginx}
access_log_rotate_bytes=${ACCESS_LOG_ROTATE_BYTES:-8388608}
error_log_rotate_bytes=${ERROR_LOG_ROTATE_BYTES:-2097152}
rotated_log_count=${ROTATED_LOG_COUNT:-3}
check_interval_seconds=${LOG_ROTATE_CHECK_INTERVAL_SECONDS:-30}
startup_grace_seconds=${LOG_ROTATE_STARTUP_GRACE_SECONDS:-60}
nginx_master_pid=${NGINX_MASTER_PID:-1}
skip_nginx_reopen=${SKIP_NGINX_REOPEN:-false}

require_positive_integer() {
  setting_name=$1
  setting_value=$2

  case "$setting_value" in
    ""|*[!0-9]*)
      printf '%s\n' "$setting_name must be a positive integer" >&2
      exit 2
      ;;
  esac

  if [ "$setting_value" -le 0 ]; then
    printf '%s\n' "$setting_name must be greater than zero" >&2
    exit 2
  fi
}

require_nonnegative_integer() {
  setting_name=$1
  setting_value=$2

  case "$setting_value" in
    ""|*[!0-9]*)
      printf '%s\n' "$setting_name must be a non-negative integer" >&2
      exit 2
      ;;
  esac
}

rotate_log_file() {
  log_path=$1
  rotate_bytes=$2

  # Remove numeric archives left by an older, larger retention count.
  for archived_log_path in "$log_path".*; do
    if [ ! -e "$archived_log_path" ]; then
      continue
    fi

    archive_suffix=${archived_log_path#"$log_path."}
    case "$archive_suffix" in
      ""|*[!0-9]*)
        continue
        ;;
    esac

    if [ "$archive_suffix" -gt "$rotated_log_count" ]; then
      rm -f "$archived_log_path"
    fi
  done

  if [ ! -f "$log_path" ]; then
    return
  fi

  log_size=$(wc -c < "$log_path")
  if [ "$log_size" -lt "$rotate_bytes" ]; then
    return
  fi

  rm -f "$log_path.$rotated_log_count"
  archive_index=$rotated_log_count
  while [ "$archive_index" -gt 1 ]; do
    previous_index=$((archive_index - 1))
    if [ -f "$log_path.$previous_index" ]; then
      mv -f "$log_path.$previous_index" "$log_path.$archive_index"
    fi
    archive_index=$previous_index
  done

  # Rename first, then ask the shared Nginx master to reopen the original path.
  # Renaming does not allocate a full-size copy when the host disk is nearly full.
  mv -f "$log_path" "$log_path.1"
  : > "$log_path"

  if [ "$skip_nginx_reopen" != "true" ] && ! kill -USR1 "$nginx_master_pid"; then
    rm -f "$log_path"
    mv -f "$log_path.1" "$log_path"
    printf 'failed to signal Nginx master PID %s after rotating %s\n' \
      "$nginx_master_pid" "$log_path" >&2
    return 1
  fi

  printf 'rotated %s at %s bytes (configured threshold: %s bytes)\n' \
    "$log_path" "$log_size" "$rotate_bytes" >&2
}

require_positive_integer ACCESS_LOG_ROTATE_BYTES "$access_log_rotate_bytes"
require_positive_integer ERROR_LOG_ROTATE_BYTES "$error_log_rotate_bytes"
require_positive_integer ROTATED_LOG_COUNT "$rotated_log_count"
require_positive_integer LOG_ROTATE_CHECK_INTERVAL_SECONDS "$check_interval_seconds"
require_positive_integer NGINX_MASTER_PID "$nginx_master_pid"
require_nonnegative_integer LOG_ROTATE_STARTUP_GRACE_SECONDS "$startup_grace_seconds"

# Give Promtail time to attach to the active inodes after a Docker Desktop or
# scoped gateway restart before the first rename can occur.
if [ "$startup_grace_seconds" -gt 0 ]; then
  sleep "$startup_grace_seconds"
fi

while :; do
  rotate_log_file \
    "$log_directory/monitoring-access.log" \
    "$access_log_rotate_bytes"
  rotate_log_file \
    "$log_directory/monitoring-error.log" \
    "$error_log_rotate_bytes"

  if [ "${RUN_ONCE:-false}" = "true" ]; then
    exit 0
  fi

  sleep "$check_interval_seconds"
done
