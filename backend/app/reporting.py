from __future__ import annotations

import smtplib
from email.message import EmailMessage

from .config import Settings


def send_report_email(settings: Settings, report: dict) -> bool:
    if not settings.report_email_to or not settings.smtp_host:
        return False

    message = EmailMessage()
    message["Subject"] = f"BuddyStuddy report: {report.get('reason', 'question')}"
    message["From"] = settings.smtp_from or settings.smtp_username or settings.report_email_to
    message["To"] = settings.report_email_to
    message.set_content(
        "\n".join(
            [
                "A BuddyStuddy community question was reported.",
                "",
                f"Report ID: {report.get('id')}",
                f"Question ID: {report.get('questionId') or report.get('id')}",
                f"Topic: {report.get('topic', '')}",
                f"Reason: {report.get('reason', '')}",
                f"Message: {report.get('message', '')}",
                f"Reporter Device: {report.get('reporterDeviceId', '')}",
                f"Author Device: {report.get('authorDeviceId', '')}",
                f"Created At: {report.get('createdAt', '')}",
                "",
                "Question:",
                str(report.get("question", "")),
            ]
        )
    )

    with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=10) as smtp:
        smtp.starttls()
        if settings.smtp_username and settings.smtp_password:
            smtp.login(settings.smtp_username, settings.smtp_password)
        smtp.send_message(message)
    return True
