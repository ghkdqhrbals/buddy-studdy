# Service Maintenance

BuddyStudy maintenance mode is owned by the monitoring control plane. It is
intended for planned work where customer apps should show a localized global
maintenance screen independently of backend API availability.

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

- The monitoring host persists maintenance windows in
  `~/buddystudy/monitoring/status/data/service-maintenance.json`.
- The monitoring status service exposes only
  `GET https://monitoring.lowfidev.cloud/status/api/v1/service-status`
  without operator authentication. All management routes remain behind the
  monitoring site's Basic Auth.
- Backend API processes and the application database do not store, publish, or
  enforce maintenance state.
- The iOS app checks monitoring status at startup, foreground entry, and every
  60 seconds. During maintenance it shows one global, non-dismissible screen
  and checks again every 15 to 120 seconds.
- When maintenance ends, the app removes the maintenance screen and resumes deferred startup synchronization automatically.
- A timeout, connectivity failure, or generic backend HTTP 503 does not open
  the maintenance screen. Only an explicit monitoring `MAINTENANCE` response
  does.

## Audit states

- `SCHEDULED`: start time is in the future.
- `ACTIVE`: current time is within the window and it has not been terminated.
- `COMPLETED`: the planned end passed or an active window was manually ended.
- `CANCELLED`: a future window was terminated before it started.

Overlapping active or scheduled windows are rejected to keep the customer-facing state unambiguous.
