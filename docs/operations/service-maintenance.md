# Service Maintenance

BuddyStudy maintenance mode is a database-backed, full-service availability control. It is intended for planned work where the consumer API must stop accepting normal traffic while operators retain access to status, health, and administration.

## Operator flow

1. Sign in to Monitoring.
2. Open `Manage > Service Status`.
3. Choose immediate activation or a future start time.
4. Optionally set an end time. Without one, maintenance continues until an operator ends it.
5. Enter Korean, English, and Japanese title and message content.
6. Save the window.
7. End active maintenance or cancel a scheduled window from the same page.

Every window remains in the paginated history with planned time, actual termination time, creator, and terminator.

## Runtime behavior

- `service_maintenance_windows` is the source of truth.
- API servers cache the current active window for at most five seconds.
- During an active window, normal `/api/v1/**` traffic receives HTTP 503 with `SERVICE_UNDER_MAINTENANCE`, localized content, timing metadata, and `Retry-After`.
- `/api/v1/service-status`, `/api/v1/health/**`, and `/api/v1/admin/**` remain available.
- The iOS app checks service status at startup, foreground entry, and every 60 seconds. During maintenance it shows one global, non-dismissible screen and checks again every 15 to 120 seconds.
- When maintenance ends, the app removes the maintenance screen and resumes deferred startup synchronization automatically.

## Audit states

- `SCHEDULED`: start time is in the future.
- `ACTIVE`: current time is within the window and it has not been terminated.
- `COMPLETED`: the planned end passed or an active window was manually ended.
- `CANCELLED`: a future window was terminated before it started.

Overlapping active or scheduled windows are rejected to keep the customer-facing state unambiguous.
