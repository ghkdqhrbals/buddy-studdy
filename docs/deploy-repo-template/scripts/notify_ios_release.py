#!/usr/bin/env python3

import json
import os
import sys
import time
import urllib.error
import urllib.request


SLACK_API_URL = "https://slack.com/api/chat.postMessage"
POLL_INTERVAL_SECONDS = 20
MAX_POLLS = 90


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required deployment value: {name}")
    return value


def request_json(
    url: str,
    *,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    payload: dict | None = None,
) -> dict:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method=method,
        headers={
            "Accept": "application/json",
            "User-Agent": "BuddyStudy-Deploy",
            **(headers or {}),
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def post_webhook(url: str, payload: dict) -> None:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        response.read()


def post_slack(token: str, payload: dict) -> dict:
    response = request_json(
        SLACK_API_URL,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
        },
        payload=payload,
    )
    if not response.get("ok"):
        raise RuntimeError(f"Slack chat.postMessage failed: {response.get('error', 'unknown_error')}")
    return response


def summary_payload(
    *,
    channel_id: str | None,
    version: str,
    build: str,
    targets: str,
    source_sha: str,
    source_ref: str,
    run_url: str,
    run_id: str,
) -> dict:
    target_label = " · ".join(
        item.strip() for item in targets.split(",") if item.strip()
    )
    payload = {
        "text": f"iOS 배포 · {version} ({build})",
        "attachments": [
            {
                "color": "#2EB67D",
                "blocks": [
                    {
                        "type": "section",
                        "text": {
                            "type": "mrkdwn",
                            "text": (
                                f"*iOS 배포*\n"
                                f"`{version} ({build})` · {target_label}"
                            ),
                        },
                    },
                    {
                        "type": "context",
                        "elements": [
                            {
                                "type": "mrkdwn",
                                "text": (
                                    f"<{run_url}|GitHub Actions>"
                                    f" · `{source_sha[:8]}`"
                                ),
                            }
                        ],
                    },
                ],
            }
        ],
    }
    if channel_id:
        payload["channel"] = channel_id
        payload["metadata"] = {
            "event_type": "buddystudy_ios_deployment",
            "event_payload": {"source_run_id": run_id},
        }
    return payload


def reply_payload(channel_id: str, thread_ts: str, text: str) -> dict:
    return {
        "channel": channel_id,
        "thread_ts": thread_ts,
        "reply_broadcast": False,
        "unfurl_links": False,
        "unfurl_media": False,
        "text": text,
    }


def workflow_jobs(source_repository: str, source_run_id: str) -> list[dict]:
    response = request_json(
        (
            f"https://api.github.com/repos/{source_repository}/actions/runs/"
            f"{source_run_id}/jobs?per_page=100"
        )
    )
    return response.get("jobs", [])


def job_by_name(jobs: list[dict], name: str) -> dict | None:
    return next((job for job in jobs if job.get("name") == name), None)


def step_by_name(job: dict | None, name: str) -> dict | None:
    if not job:
        return None
    return next((step for step in job.get("steps", []) if step.get("name") == name), None)


def failed_part(build_job: dict | None, upload_job: dict | None) -> tuple[str, str] | None:
    for label, job in (("IPA 빌드", build_job), ("TestFlight 업로드", upload_job)):
        if job and job.get("conclusion") in {"failure", "cancelled", "timed_out"}:
            return label, job["conclusion"]
    return None


def monitor_thread(
    *,
    token: str,
    channel_id: str,
    thread_ts: str,
    source_repository: str,
    source_run_id: str,
    run_url: str,
    upload_enabled: bool,
) -> int:
    sent: set[str] = set()

    def reply(key: str, text: str) -> None:
        if key in sent:
            return
        post_slack(token, reply_payload(channel_id, thread_ts, text))
        sent.add(key)

    for _ in range(MAX_POLLS):
        jobs = workflow_jobs(source_repository, source_run_id)
        build_job = job_by_name(jobs, "Build Signed iOS IPA")
        upload_job = job_by_name(jobs, "Upload iOS IPA to TestFlight")

        failure = failed_part(build_job, upload_job)
        if failure:
            label, conclusion = failure
            result = "취소" if conclusion == "cancelled" else "실패"
            reply("failure", f"❌ {label} {result}\n<{run_url}|실행 보기>")
            return 1

        build_step = step_by_name(build_job, "Build iOS debug target")
        if build_step and build_step.get("status") == "in_progress":
            reply("build_started", "1/4 · 빌드 검증 중")

        archive_step = step_by_name(build_job, "Archive iOS app")
        if archive_step and archive_step.get("status") == "in_progress":
            reply("archive_started", "2/4 · 서명 아카이브 생성 중")

        if build_job and build_job.get("conclusion") == "success":
            reply("build_completed", "3/4 · IPA 준비 완료")

        upload_step = step_by_name(upload_job, "Upload to App Store Connect for TestFlight")
        if upload_step and upload_step.get("status") == "in_progress":
            reply("upload_started", "4/4 · TestFlight 업로드 중")

        if upload_job and upload_job.get("conclusion") == "success":
            reply(
                "upload_completed",
                f"✅ TestFlight 접수 완료\nApple 처리 중 · <{run_url}|실행 보기>",
            )
            return 0

        if not upload_enabled and build_job and build_job.get("conclusion") == "success":
            reply("artifact_completed", f"✅ IPA 생성 완료\n<{run_url}|실행 보기>")
            return 0

        if upload_job and upload_job.get("conclusion") == "skipped":
            reply("upload_skipped", f"✅ IPA 생성 완료\nTestFlight 생략 · <{run_url}|실행 보기>")
            return 0

        time.sleep(POLL_INTERVAL_SECONDS)

    reply("timeout", f"⚠️ 상태 추적 시간이 초과됐습니다 · <{run_url}|실행 보기>")
    return 1


def main() -> int:
    version = required("RELEASE_VERSION")
    build = required("RELEASE_BUILD_NUMBER")
    targets = required("DEPLOYMENT_TARGETS")
    source_repository = required("SOURCE_REPOSITORY")
    source_sha = required("SOURCE_SHA")
    source_ref = required("SOURCE_REF")
    source_run_id = required("SOURCE_RUN_ID")
    upload_enabled = os.environ.get("UPLOAD_ENABLED", "false") == "true"

    if source_repository != "ghkdqhrbals/buddy-studdy":
        raise RuntimeError(f"Unsupported deployment source: {source_repository}")

    run_url = (
        f"https://github.com/{source_repository}/actions/runs/{source_run_id}"
    )
    bot_token = os.environ.get("DEPLOY_SLACK_BOT_TOKEN", "").strip()
    channel_id = os.environ.get("DEPLOY_SLACK_CHANNEL_ID", "").strip()

    if bot_token and channel_id:
        response = post_slack(
            bot_token,
            summary_payload(
                channel_id=channel_id,
                version=version,
                build=build,
                targets=targets,
                source_sha=source_sha,
                source_ref=source_ref,
                run_url=run_url,
                run_id=source_run_id,
            ),
        )
        thread_ts = response.get("ts")
        if not thread_ts:
            raise RuntimeError("Slack did not return a parent message timestamp.")
        return monitor_thread(
            token=bot_token,
            channel_id=channel_id,
            thread_ts=thread_ts,
            source_repository=source_repository,
            source_run_id=source_run_id,
            run_url=run_url,
            upload_enabled=upload_enabled,
        )

    webhook_url = (
        os.environ.get("DEPLOY_SLACK_WEBHOOK_URL", "").strip()
        or os.environ.get("LEGACY_SLACK_WEBHOOK_URL", "").strip()
    )
    if not webhook_url:
        raise RuntimeError(
            "Missing DEPLOY_SLACK_BOT_TOKEN/DEPLOY_SLACK_CHANNEL_ID and webhook fallback."
        )

    post_webhook(
        webhook_url,
        summary_payload(
            channel_id=None,
            version=version,
            build=build,
            targets=targets,
            source_sha=source_sha,
            source_ref=source_ref,
            run_url=run_url,
            run_id=source_run_id,
        ),
    )
    print(
        "DEPLOY_SLACK_BOT_TOKEN is unavailable; posted only the compact parent summary.",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, urllib.error.URLError, json.JSONDecodeError) as error:
        print(f"Deployment Slack notification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
