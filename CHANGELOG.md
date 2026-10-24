## [1.0.0-rc.1] - 2026-10-24

Release candidate of 1.0.0 for the security review. The API can still change before 1.0.0.


- First release.

### Added

- The native card flow (CONTRACT §16): your server creates the order with its secret key and gives the app the order's
  `orderId` and `clientSecret`; the app holds only the publishable key. The SDK never creates orders.
- `PayriffPaymentView` (CONTRACT §16.14), the ready-made card payment screen, like the card part of Payriff's hosted
  payment page (pay.payriff.com): the card form, a Pay button with the order's amount ("Pay 1.02 AZN"), the
  Mastercard and Visa badges, the secure line and a "Powered by Payriff" footer, whose logo has a light wordmark
  (`#E1E4E8`, `payriff_logo_dark`) in the dark theme. It reads the order when it appears,
  pays with a launcher of its own and passes the result of every payment attempt, and once a fatal error that ends the
  order, to `onResultListener`. Works full screen, in a bottom sheet and in a dialog, follows the system light or dark
  theme, and gives a result that arrives after the activity was recreated during 3-D Secure to the view with the same
  id.
- `PayriffPaymentViewStyle`: the background, the padding, the Pay button's colour, text style, height and corner radius,
  the secondary text colour, `showBadges`, `showSecureNote`, `showPoweredBy` and the form's `formStyle` (Kotlin named
  arguments and a Java builder).
- Text overrides on `PayriffPaymentViewStyle` (CONTRACT §16.14): `payButtonLabel` replaces the word "Pay" (the amount
  and the currency still follow it, "Pay now 1.02 AZN", and it shows alone while the amount is unknown) and
  `secureNoteText` replaces "Proceed securely by PCI DSS". Both are shown as they are in every language; `null` or a
  blank text keeps the localized copy.
- `Payriff.configure` with `PayriffConfig`: `publishableKey`, `environment`, `language`, `enableLogging` and
  `allowScreenCaptureInDebug` (Kotlin named arguments and a Java builder). `configure` never throws; a key that is not
  a publishable key is logged as a warning, and card calls then fail with `INVALID_CONFIG`.
- `PayriffCardForm`, a native card form (Views, Compose through `AndroidView`, Java): Visa and Mastercard, the Luhn
  check, an expiry at most 20 years ahead, a 3-digit CVV and the cardholder name, which is shown by default
  (`showHolder = false` hides it and sends the name `Unknown`, as on the web); `autoAdvance`, `language`, `style` and
  change events that never carry the typed values.
- The card form looks like the hosted payment page's by default, with the cardholder name and one "Card information"
  box, and `PayriffCardFormStyle` changes its layout (`GROUPED` or `STACKED`), text, colours and sizes (CONTRACT §16.4).
- `PayriffTextStyle` sets only what it changes: its `size`, `weight`, `color` and `typeface` are optional, and every
  text style of `PayriffCardFormStyle` and `PayriffPaymentViewStyle` is merged over the default of its text, as in the
  iOS and Flutter SDKs. `buttonTextStyle = PayriffTextStyle(color = …)` keeps the button's 16 sp semibold.
- Card numbers have at most 16 digits for every brand (CONTRACT §16.4): typing stops at 16 digits and a 19-digit Visa
  number is refused, although the web SDK accepts it.
- The card number field shows the brand's mark (CONTRACT §16.4): the Visa wordmark or the Mastercard circles on
  a 36×24 dp white badge with a `#D1D1D6` border, a neutral card outline while the brand is unknown, a 150 ms
  cross-fade, and "Visa" or "Mastercard" for accessibility services.
- The cardholder name is always upper case, with the locale-independent mapping (CONTRACT §16.4).
- `PayriffCardPaymentLauncher` (callback and `suspend` `confirm`): validates the form, checks that the order is payable
  by card, encrypts the card on the device (AES-256-GCM, key wrapped with RSA-OAEP SHA-256 and Payriff's pinned key),
  sends it, runs the bank's 3-D Secure step and polls the status. Results that arrive after the activity was recreated
  go to `onRestoredResult`.
- Certificate pinning on the SDK's own HTTP client (CONTRACT §16.15): every Client API call (order, pay, 3-D Secure
  step, status) to `api.payriff.com` and `sb-api.payriff.com` keeps Android's TLS validation and also requires a pinned
  Payriff CA key. A pinning failure is a non-retryable `NETWORK_ERROR` ("Payriff's server certificate could not be
  verified.") and nothing is sent. The pins are CA keys, not the leaf; there is no switch to turn pinning off; the
  3-D Secure WebViews are not pinned.
- Device integrity (CONTRACT §16.16): card payments are refused on a rooted or tampered device or when a hooking
  framework (Frida, Xposed/LSPosed) is loaded, with `DEVICE_COMPROMISED`. `Payriff.isDeviceCompromised(context)` lets
  the app check early; `PayriffPaymentView` shows "Card payments aren't available on this device…", keeps Pay disabled,
  skips the order load and reports the failure to `onResultListener` once. `retrieveOrder` is never blocked. The check
  runs when the view appears, at the start of `confirmCardPayment`, and again just before encryption; file checks are
  cached for the process while the Frida/hooking/debugger checks run fresh. It fails closed: a check that cannot
  complete (a probe throws) counts as compromised with the signal `check failed`, and `isDeviceCompromised` returns
  `true`.
- `PayriffConfig.allowCompromisedDevices` (default `false`): for development in debuggable apps only, lets card payments
  run on a rooted or hooked device, or after a check that could not complete, with a warning that names the matched
  signals, instead of refusing them. Release builds ignore it (see Security).
- `Payriff.retrieveOrder` with `PayriffClientOrder` (suspending, plus a Java callback variant).
- A typed `PayriffResult` (`Success`, `Declined`, `Cancelled`, `Pending`, `Failed`) with the JSON shape shared by every
  Payriff SDK, and `PayriffError` with `isFatal`, `fields` and the card error codes, including `DEVICE_COMPROMISED`.
- The 3-D Secure sheet (CONTRACT §9.1, §16.8): the title alone in the header (no address, padlock or host), a native
  loader instead of a progress bar, a close button that is disabled while the payment is checked, a close confirmation
  followed by one status check, no zoom, no overscroll effect or scrollbars, and the bank page inset 16 dp from the
  sides and 12 dp from the top and bottom on a white backdrop. It survives rotation and theme changes, and resumes with
  one status check after process death.
- The 3-D Secure sheet's close confirmation says what closing does (CONTRACT §9.2, §10): "Close this window?", "The
  payment isn't finished yet. If you close this window, we'll check its status." and "Close", in Azerbaijani, English
  and Russian. Closing checks the status once; it never cancels the order.
- `PayriffPaymentView` shows "This order cannot be paid by card" under the Pay button, in the view's language, when the
  order loaded but cannot be paid by card (`view.notPayable`, CONTRACT §16.14).
- `PayriffLogger` with privacy-safe log entries (`PayriffLogEntry.formatted`, `timestampMillis`).
- HTTP details (CONTRACT §11): with logging on in a debuggable app, every Client API call also logs its request and
  response as pretty-printed JSON with headers and body at `DEBUG` level, with keys, client secrets, card fields and
  bank form values redacted, card numbers cut to their last 4 digits and URLs redacted as in the rest of the log (see
  Security); release builds never log them.
- Helpers shared with the other Payriff SDKs: `Payriff.version`, `Payriff.isPaymentInProgress`,
  `PayriffEnvironment.apiBaseUrl`, `PayriffPaymentStatus.fromRaw`, and `isSuccess`, `payment`, `error` and `toJson()`
  on every `PayriffResult`.
- Example app (`sample`): one screen, like the iOS and Flutter examples, with `orderId` and `clientSecret` fields and
  Paste (the JSON `{"orderId", "clientSecret"}` or the two values separated by white space), then "Pay in page", "Pay
  in bottom sheet" and "Pay in popup", which show `PayriffPaymentView` for that order in an activity, in a
  `BottomSheetDialogFragment` sized to its content or in a `DialogFragment`, and the outcome in a result dialog. The
  publishable key and the environment come from the git-ignored `local.properties`, in debug builds only. It holds no
  secret key and creates no orders.

### Security

- Certificate pinning (CONTRACT §16.15): the SDK's Client API connections are pinned to Payriff's CA keys, so a
  debugging proxy or a mis-issued certificate cannot read or alter card traffic. Pinning cannot be turned off, and the
  pins and trust anchors are checked against `vectors/tls_pins.json`.
- Device integrity (CONTRACT §16.16): rooted/tampered devices and loaded hooking frameworks (Frida, Xposed) are
  detected and card payments refused by default, at three points of every payment. The check fails closed: when it
  cannot complete, the device counts as compromised (`check failed`), so breaking the check does not skip it. The
  warning names the matched signals (for example `su binary`, `Magisk`, `Frida`) and never file contents. The manifest declares the root-manager
  and Xposed packages in `<queries>` so they can be seen on Android 11+, without the `QUERY_ALL_PACKAGES` permission.
- `PayriffConfig.allowCompromisedDevices` works only in debuggable apps (`ApplicationInfo.FLAG_DEBUGGABLE`, CONTRACT
  §16.16): a release build ignores it, logs a warning that it is ignored outside debug builds and refuses a compromised
  device as by default, so leaving it on cannot reach production.
- URLs in the logs (CONTRACT §11): one redaction covers the general log, the HTTP details and URLs inside logged
  bodies. User info (`user:password@`), queries and fragments are never logged. The path is kept only for Payriff's API
  hosts (`api.payriff.com`, `sb-api.payriff.com`), with every segment of 16 or more characters cut to its first and last
  4 characters (`/api/v3/client/orders/7c9e…0ae7/pay`); any other host, such as a bank's 3-D Secure page, is logged as
  `scheme://host[:port]`, and any other scheme as `<scheme>:…`.
- The secret key is never part of the SDK or the example app: no configuration, call or test uses it.
- The card form's window, the 3-D Secure sheet and its close confirmation use `FLAG_SECURE`, and on Android 14+ the
  bank page is marked accessibility-data-sensitive. Debuggable apps may opt out with
  `PayriffConfig.allowScreenCaptureInDebug`.
- The SDK removes the website data of its 3-D Secure pages when the payment ends (CONTRACT §9.1a): it records the hosts
  and paths of the pages and frames its WebViews request, then removes their storage with `WebStorage.deleteOrigin`,
  expires their cookies — for `Path=/` and for every path prefix of each page's URL, with and without the `/` that
  follows it in the path (`/acs`, `/acs/`), as the Flutter SDK does, for the host and its parent domains, with
  `; Secure` for https pages so `__Secure-`/`__Host-` cookies go too — and clears the WebView HTTP cache.
  Android keeps WebView data per app, so the app's own data for those hosts goes too, and the cache is cleared for the
  whole app. Payments that never showed a WebView touch nothing.
- Card data never leaves the SDK except as ciphertext; it is never logged, never saved in the instance state and is
  cleared after a final result. Encryption buffers are zeroed.
- Links to other apps never crash the host app: malformed `intent:` URLs and refused launches are logged and
  ignored, links with `file:` or `content:` data and links over 64 KB are refused, and an `intent:` fallback loads
  only over https.
- A pathologically nested API response is treated as unparseable instead of overflowing the stack.
- README: "Fulfil on your server", the rules for fulfilling only after your backend confirmed the order.
- `SECURITY.md`: how to report a vulnerability privately, through GitHub's private vulnerability reporting.

<!-- Replace "Unreleased" with the release date when publishing. The format follows Keep a Changelog
     (https://keepachangelog.com/en/1.1.0/) and the project uses Semantic Versioning (https://semver.org/). -->
