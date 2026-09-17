# Changelog

All notable DriveTouch Verifier changes will be documented here.

## v0.1.0 - 2026-09-17

Initial stable DriveTouch Verifier build.

### Added

- Accessibility-service logging for app switches, clicks, scrolls, and focused views.
- Configurable evidence period: 5, 10, or 15 minutes.
- Strict rolling retention with pruning, watchdog cleanup, and a hard row cap.
- Immutable saved CSV evidence snapshots.
- In-app saved CSV viewer with app and event filters.
- Safe-app highlighting for easier review of navigation apps.
- Quick Settings tile for silent evidence saving.
- First-run guide and reusable in-app guide.
- Local-only storage with no network access.

### Notes

- Accessibility events are not raw physical touch events.
- DriveTouch does not capture screen contents, coordinates, screenshots, or gesture duration.
- Saved CSV files are created from the selected evidence period and remain in the app until deleted.
