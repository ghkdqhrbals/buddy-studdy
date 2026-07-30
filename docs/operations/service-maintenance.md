# Service Maintenance

BuddyStudy maintenance mode uses Firebase Remote Config as the only customer
app delivery channel. The monitoring site is an authenticated operator UI; it
does not own a separate status service.

## Operator flow

1. Sign in to Monitoring.
2. Open `Manage > Service Status`.
3. Choose immediate activation or a future start time.
4. Optionally set an end time. Without one, maintenance continues until an
   operator ends it.
5. Enter Korean, English, and Japanese title and message content.
6. Save the window.
7. End active maintenance or cancel a scheduled window from the same page.

Every window remains in the backend's paginated audit history with planned
time, actual termination time, creator, and terminator.

## Runtime behavior

1. Monitoring calls the authenticated backend `/api/v1/admin/app-updates/maintenance`
   API.
2. The backend stores the window in `app_control_maintenance_windows`.
3. The backend publishes the resulting app-control document to Firebase Remote
   Config.
4. iOS fetches the policy during startup and foreground entry and registers a
   Remote Config real-time update listener while the app is running.
5. An active policy shows one global, non-dismissible maintenance screen. A
   scheduled policy becomes active at its local time boundary without polling.
6. Ending maintenance republishes the policy; the listener fetches the
   activated configuration and removes the screen.

The app fails open when Firebase Remote Config is unavailable. Generic backend
errors and HTTP 503 responses do not activate maintenance mode. There is no
public monitoring status endpoint and no periodic maintenance polling loop.

## Audit states

- `SCHEDULED`: start time is in the future.
- `ACTIVE`: current time is within the window and it has not been terminated.
- `COMPLETED`: the planned end passed or an active window was manually ended.
- `CANCELLED`: a future window was terminated before it started.

Overlapping active or scheduled windows are rejected to keep the customer-facing state unambiguous.
