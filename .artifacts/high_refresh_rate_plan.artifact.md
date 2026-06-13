# High Refresh Rate (120Hz) Optimization Plan

The user reports that animations feel "trapped in 60Hz" and laggy. This plan aims to unlock high refresh rates (120Hz+) where available and optimize Jetpack Compose to fit within the shorter frame budget (8.33ms at 120Hz).

## Proposed Changes

### [Core]
Enable high refresh rate requests at the window level.

#### [MainActivity.kt](file:///home/yu/StudioProjects/resonance/app/src/main/java/dev/yuwixx/resonance/MainActivity.kt)
- Use the Frame Rate API (`window.setFrameRate`) to request the maximum available refresh rate (120Hz).
- Set `Surface.FRAME_RATE_COMPATIBILITY_DEFAULT` to allow the system to manage power when appropriate.

### [UI Performance]
Ensure animations do not trigger unnecessary recompositions.

#### [Components.kt](file:///home/yu/StudioProjects/resonance/app/src/main/java/dev/yuwixx/resonance/presentation/components/Components.kt)
- Audit all `animate*AsState` usages.
- Ensure state reads are deferred using lambda modifiers (e.g., `graphicsLayer { alpha = animatedAlpha }` instead of `Modifier.alpha(animatedAlpha)`).
- Review `MaterialYou3Seekbar` to ensure the `displayProgress` calculation doesn't cause a full recomposition chain.

#### [LibraryScreens.kt](file:///home/yu/StudioProjects/resonance/app/src/main/java/dev/yuwixx/resonance/presentation/screens/LibraryScreens.kt)
- Add `Modifier.preferredFrameRate(120f)` to key animated transitions if using Compose 1.7+.
- Optimize `SongCard` list items to ensure they stay within the 8ms budget during fast scrolling.

## Verification Plan

### Automated Tests
- Run `:app:assembleDebug` to ensure compilation.

### Manual Verification
- **FPS Counter**: Enable "Show refresh rate" in Developer Options to verify the app hits 120Hz during animations.
- **Jank Tracking**: Use "Profile GPU rendering" to ensure frame bars stay below the 8.33ms line (the green line is usually for 60fps/16ms, so we look for half that).
- **Smoothness Check**: Manually verify that the `MiniPlayer` entry/exit and `PlayerScreen` transitions feel "buttery" on a high refresh rate device.
