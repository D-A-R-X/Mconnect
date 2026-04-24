# Mconnect — Manju Groups PMS

Android mobile app for Manju Groups Project Management System. OTP-based authentication, HR management, attendance tracking, leave management, chat, and more.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.1.20 |
| UI | XML Layouts + ViewBinding |
| Architecture | MVVM (ViewModel + StateFlow) |
| Networking | Retrofit 2.11 + OkHttp 4.12 + Gson |
| Security | EncryptedSharedPreferences (AES256-GCM) |
| Async | Kotlin Coroutines |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 |

## Design System

### Color Tokens

All colors use semantic attributes (`?attr/colorXxx`) defined in `res/values/attrs.xml`. Never reference raw colors directly in layouts or drawables.

| Attribute | Dark | Light | Usage |
|-----------|------|-------|-------|
| `colorAccentPrimary` | #A855F7 | #7C3AED | Buttons, links, active states |
| `colorSurfacePrimary` | #0A0A0A | #FFFFFF | Screen backgrounds |
| `colorSurfaceSecondary` | #1A1A1A | #F4F4F5 | Input fields, cards |
| `colorForegroundPrimary` | #FFFFFF | #18181B | Primary text |
| `colorForegroundSecondary` | #A1A1AA | #52525B | Subtitles, labels |
| `colorForegroundMuted` | #71717A | #A1A1AA | Hints, placeholders |
| `colorForegroundInverse` | #0A0A0A | #FFFFFF | Text on accent bg |
| `colorBorder` | #27272A | #E4E4E7 | Input borders |
| `colorBorderLight` | #3F3F46 | #F4F4F5 | Dividers |
| `colorError` | #EF4444 | #DC2626 | Error states |

### Typography

Two font families bundled in `res/font/`:

- **Inter** — all UI text (Regular 400, Medium 500, SemiBold 600, Bold 700)
- **Geist Mono** — OTP digits and timer display (Regular 400, SemiBold 600)

TextAppearance styles defined in `res/values/type.xml`:

| Style | Font | Size | Weight |
|-------|------|------|--------|
| `HeadingLg` | Inter | 28sp | Bold |
| `Body` | Inter | 15sp | Regular |
| `Label` | Inter | 14sp | Medium |
| `Button` | Inter | 16sp | SemiBold |
| `BrandLabel` | Inter | 12sp | SemiBold + letterSpacing |
| `OtpDigit` | Geist Mono | 22sp | SemiBold |
| `Timer` | Geist Mono | 13sp | Regular |

### Spacing (from `dimens.xml`)

| Token | Value | Usage |
|-------|-------|-------|
| `screen_padding_horizontal` | 24dp | Screen side padding |
| `screen_padding_top` | 40dp | Top padding |
| `section_gap` | 32dp | Between major sections |
| `field_gap` | 8dp | Label to input |
| `input_height` | 52dp | Input field height |
| `button_height` | 52dp | Primary button height |
| `otp_box_width` x `otp_box_height` | 48x56dp | OTP digit box |
| `input_corner` / `button_corner` | 12dp | Corner radius |

## Theme System

The app follows system dark/light mode (`MODE_NIGHT_FOLLOW_SYSTEM`).

```
res/values/themes.xml       ← Light theme (default)
res/values-night/themes.xml ← Dark theme
res/values/attrs.xml        ← Semantic color attributes
res/values/colors.xml       ← Raw color values (dk_* and lt_*)
```

**How it works:** Layouts and drawables reference `?attr/colorXxx`. The theme maps these to either `dk_*` or `lt_*` raw colors automatically.

**Rule:** Never use `@color/dk_*` or `@color/lt_*` in layouts. Always use `?attr/colorXxx`.

## Architecture

```
com.manjugroups.m_connect/
├── MconnectApp.kt              ← Application class (night mode setup)
├── MainActivity.kt             ← Post-login landing
├── auth/
│   ├── LoginActivity.kt        ← Phone input screen
│   ├── OtpActivity.kt          ← OTP verification screen
│   ├── AuthViewModel.kt        ← Auth state (StateFlow + sealed interface)
│   └── SessionManager.kt       ← Encrypted token storage
└── network/
    └── ApiService.kt           ← Retrofit API client + data classes
```

### State Management

- `AuthViewModel` uses `StateFlow<AuthUiState>` (not LiveData)
- Activities collect with `repeatOnLifecycle(STARTED)` pattern
- States: `Idle`, `Loading`, `OtpSent`, `Verified`, `Error`

### API

Base URL: `https://opulent-cricket-895.convex.site/`

Auth flow:
1. `POST /api/auth/send-otp` → sends OTP to phone
2. `POST /api/auth/verify-otp` → returns Bearer token + user info
3. Token stored in EncryptedSharedPreferences
4. All authenticated requests use `Authorization: Bearer <token>`

## Conventions

1. **Colors**: Always `?attr/colorXxx`, never raw hex or `@color/`
2. **Dimensions**: Always `@dimen/xxx` for reusable spacing, never magic numbers
3. **Typography**: Always `android:textAppearance="@style/TextAppearance.Mconnect.Xxx"`
4. **Fonts**: Always `@font/inter_*` or `@font/geist_mono_*`, never `sans-serif`
5. **Drawables**: Use `?attr/` references so they theme-switch automatically
6. **State**: Use `StateFlow` + `sealed interface`, collect with `repeatOnLifecycle`
7. **Naming**: layouts `activity_*.xml`, drawables `bg_*` / `ic_*`, dimens lowercase_snake

## Build & Run

```bash
./gradlew assembleDebug
```

Test login: phone `9000000001`, OTP `000000` (dev mode)
