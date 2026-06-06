from __future__ import annotations

import smtplib
from email.message import EmailMessage
from enum import Enum

from .config import Settings


class EmailDeliveryFailureReason(str, Enum):
    SEND_FAILED = "send_failed"
    QUOTA_EXCEEDED = "quota_exceeded"


class EmailDeliveryError(RuntimeError):
    def __init__(self, reason: EmailDeliveryFailureReason, message: str):
        super().__init__(message)
        self.reason = reason


def smtp_configured(settings: Settings) -> bool:
    return bool(settings.smtp_host and settings.smtp_username and settings.smtp_password)


def _email_delivery_error(error: smtplib.SMTPException) -> EmailDeliveryError:
    response = ""
    code = None
    if isinstance(error, smtplib.SMTPResponseException):
        code = int(error.smtp_code)
        response = str(error.smtp_error or "")

    normalized = response.lower()
    quota_markers = [
        "quota",
        "limit",
        "rate",
        "too many",
        "daily user sending",
        "user-rate",
    ]
    if code in {421, 450, 451, 452, 454, 550, 552} and any(marker in normalized for marker in quota_markers):
        return EmailDeliveryError(
            EmailDeliveryFailureReason.QUOTA_EXCEEDED,
            "Email verification email quota exceeded.",
        )

    return EmailDeliveryError(
        EmailDeliveryFailureReason.SEND_FAILED,
        "Email verification email could not be sent.",
    )


def send_email_verification_code(settings: Settings, email: str, code: str, ttl_seconds: int) -> bool:
    if not smtp_configured(settings):
        return False

    message = EmailMessage()
    message["Subject"] = "BuddyStuddy verification code"
    message["From"] = settings.smtp_from or settings.smtp_username
    message["To"] = email
    message.set_content(
        "\n".join(
            [
                "Your BuddyStuddy verification code is:",
                "",
                code,
                "",
                f"This code expires in {ttl_seconds} seconds.",
                "If you did not request this code, you can ignore this email.",
            ]
        )
    )

    try:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=10) as smtp:
            smtp.starttls()
            smtp.login(settings.smtp_username, settings.smtp_password)
            smtp.send_message(message)
    except smtplib.SMTPException as error:
        raise _email_delivery_error(error) from error
    return True


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
