# DriveTouch Verifier

DriveTouch Verifier is a local-only Android app for saving a quick evidence snapshot of recent Android accessibility interaction events. It is designed for the simple driving scenario: keep a short rolling window, tap the app or Quick Settings tile, and save a CSV that can be viewed or shared later.

The app does not use the network.

## What It Records

- App/window switches
- View clicks
- View scrolls
- View focus events

Android accessibility events are not raw touch input. DriveTouch does not capture coordinates, physical `MotionEvent`s, screenshots, or gesture duration. Saved files should be treated as evidence of interaction events exposed through Android accessibility, not a complete physical touch trace.

## Evidence Window

DriveTouch keeps a rolling local database window of recent events. The evidence period is configurable in-app:

- 5 minutes
- 10 minutes
- 15 minutes

When you save evidence, DriveTouch creates an immutable CSV snapshot from the current evidence period. Saved CSVs stay in the app until you delete them.

## Storage Safety

The rolling event table is intentionally bounded:

- Expired rows are pruned when the accessibility service starts.
- Rows are pruned before and after each insert.
- A watchdog prunes every 30 seconds while the service is alive.
- DriveTouch's own UI events are excluded from the rolling log.
- The rolling table has a hard cap of 5,000 newest rows as a final backstop.

Saved evidence CSVs are separate from the rolling table and are deleted only when you explicitly delete them.

## App Features

- Compose dashboard with system light/dark theme.
- Accessibility Settings shortcut.
- Safe-app highlighting for apps such as Waze or Google Maps.
- Saved evidence CSV viewer with app and event filters.
- Android share sheet export for saved CSVs.
- Quick Settings tile: **Save DriveTouch**.

## Privacy

DriveTouch stores data locally only. It does not send logs to a server.

The app requests package visibility so it can resolve human-readable app names for logged package names.

## Build

```sh
./gradlew assembleDebug
```

## Use

1. Install and open the app.
2. Tap **Settings** and enable **DriveTouch Verifier** in Android Accessibility Settings.
3. Choose an evidence period.
4. Add the **Save DriveTouch** Quick Settings tile if you want one-swipe saving.
5. Tap **Save Evidence CSV** in the app, or tap the Quick Settings tile, to save a snapshot.
6. View, filter, share, or delete saved CSVs from the app.

## Current Limitations

- Android does not allow apps to enable or disable their own Accessibility Service programmatically.
- Some apps expose fewer accessibility events than others.
- `VIEW_FOCUSED` means a UI element became focused; it is context, not necessarily a tap.
