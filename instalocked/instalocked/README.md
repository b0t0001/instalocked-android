# InstaLocked

A personal Android app that reshapes Instagram into something closer to a
messaging client, without modifying Instagram itself. It reads the screen via
an accessibility service and draws on top of it.

Built for one device: Moto G Power (2021), Android 11.

## What it does

| Requirement | Mechanism | Fidelity |
|---|---|---|
| Reels gated behind a 30-word typed reason | `ScreenClassifier` → `PolicyEngine` → `GateActivity` | full |
| Copy/paste disabled in the gate | three independent layers (see `NoPasteEditText`) | full |
| 5-minute session, then re-block | `SessionService` foreground countdown | full |
| Story rings greyscale, rest in colour | grey annuli drawn over ring positions | **approximation** |
| Home feed capped at 20 | `AccessibilityEvent.toIndex` counter | full |
| Only people you follow | "Suggested for you" marker ends the feed | full |
| Reels friends send you: full colour, no gate | `REELS_FROM_DM` via provenance + chrome | full |
| ...but no scrolling onward from them | scroll index change → exit viewer | reactive |
| Posting and creating untouched | `REELS_CREATE` classified first, always allowed | full |
| Adding friends untouched | `SEARCH` classified before `EXPLORE_GRID` | full |
| No internet, nothing stored remotely | no `INTERNET` permission in the manifest | full |
| Lightweight | zero dependencies beyond kotlin-stdlib | ~300 KB APK |

## The one thing it cannot do properly

Android has no API to desaturate another app's pixels. System-wide greyscale
would grey out everything, which you explicitly did not want. So the ring
masking paints opaque grey donuts over where the gradient rings are, computed
from story-tray item bounds.

Practical consequences:

- During a fast fling of the story tray, the donuts trail the real rings by a
  frame or two. Bounds only arrive when accessibility reports them.
- The geometry needs one calibration pass. Three fractions in `selectors.json`
  control it: `ringOuterFraction`, `ringThicknessFraction`,
  `ringVerticalBiasFraction`.

Everything else on the list is exact.

## Architecture

```
GuardService          AccessibilityService. Event router only, no logic.
 └─ NodeScan          Bounded single-pass tree walk (900 nodes, depth 28).
 └─ ScreenClassifier  Node tree → Screen enum, driven by selectors.json.
 └─ PolicyEngine      Pure function: (Screen, State, Config) → Decision.
 └─ OverlayManager    The only component that draws.
     └─ RingMaskView  Canvas annuli over story rings.
 └─ GateActivity      The 30-word screen.
 └─ SessionService    Foreground countdown, re-arms the block at zero.
 └─ Store             SharedPreferences + two JSONL files. No database.
```

Two deliberate design rules:

**Fail open.** Any screen the classifier does not recognise returns `UNKNOWN`,
which the policy engine allows. When Instagram renames a view ID, the app
degrades to "no restrictions", never to "locked out of your messages before an
exam".

**Provenance, not just structure.** A reel a friend sent and a reel the
algorithm served are frequently the same view hierarchy, so structure alone
cannot separate them. The classifier carries how recently you were in a DM
thread (25s window) and treats a reel viewer opened inside that window as
shared content: full colour, no gate, no countdown. Shared-reel chrome (a reply
composer, sender attribution) is the confirming signal and the fallback for
when you linger before tapping.

Note the ordering consequence: `REELS_FROM_DM` must be tested *before* the
generic `DMS` rule, because a shared reel carries a reply composer that would
otherwise let `DMS` claim it and skip the no-scroll enforcement entirely.

**Create beats consume.** `REELS_CREATE` is tested before `REELS_CONSUME`, and
there is a 90-second grace window after leaving a creation screen during which
landing in the Reels pager is treated as reviewing your own upload. Instagram
routinely drops you into the pager at your new post; without the grace window
the gate would fire on your own content.

## Calibration loop

The selectors in `app/src/main/assets/selectors.json` are educated guesses
against a recent Instagram build. They will need adjusting for your exact
version, and again whenever Instagram ships a significant update.

1. Open InstaLocked → Calibration → **Start capture (3 min)**
2. Open Instagram and visit the screen that behaved wrong
3. Return to InstaLocked → **View last dump**
4. Adjust the matchers, drop the file at
   `/data/data/com.instalocked/files/selectors.json`, tap **Reload selectors.json**

No rebuild, no reinstall. This is the whole maintenance story.

## Building

There is no committed `gradle-wrapper.jar`. CI generates one.

Push to GitHub and the workflow in `.github/workflows/build.yml` produces both
a debug and a release APK as a downloadable artifact. Or locally, with Android
Studio or a JDK 17 + Gradle 8.9 toolchain:

```
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Both variants are debug-signed, which is fine for sideloading to your own
device. Keep using the same APK lineage so updates install over the top and
your config and logs survive.

## Escape hatch

You can turn the accessibility service off in three taps. Every app blocker has
this hole and none of them close it properly. The daily session limit
(`dailySessionLimit`, default 4) and the near-duplicate rejection on reasons are
the real friction; the rest is a speed bump you have consented to.
