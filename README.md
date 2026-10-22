# Payriff Android SDK

Accept Payriff card payments in Android apps. Your server creates the order with its secret key and gives the app the order's `orderId` and `clientSecret`. The app shows `PayriffPaymentView`, a ready-made payment screen that pays the order with your **publishable key**, runs 3-D Secure in a native sheet and returns a typed `PayriffResult`. For a screen of your own, use the `PayriffCardForm` and `PayriffCardPaymentLauncher` it is built on.

- A ready-made payment view for Views, Compose and Java. By default it looks like the card part of Payriff's hosted payment page (pay.payriff.com); you can change its colours, sizes and parts
- A native card form (Visa and Mastercard), validated and formatted as the customer types, in Azerbaijani, English and Russian, in the light and the dark theme
- Only the **publishable key** in the app: the secret key stays on your server
- Card data is encrypted on the device (AES-256-GCM, key wrapped with RSA-OAEP SHA-256 and Payriff's pinned key) and leaves it only as ciphertext
- 3-D Secure in a native bottom sheet; survives rotation, theme changes and process death

Documentation of the Payriff API: <https://docs.payriff.com>

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [The payment view](#the-payment-view)
  - [Full screen, bottom sheet or dialog](#full-screen-bottom-sheet-or-dialog)
  - [Customise the payment view](#customise-the-payment-view)
- [Build your own screen](#build-your-own-screen)
  - [The card form](#the-card-form)
  - [Customise the card form](#customise-the-card-form)
- [Order details](#order-details)
- [Handling the result](#handling-the-result)
- [Fulfil on your server](#fulfil-on-your-server)
- [Errors](#errors)
- [Helpers](#helpers)
- [Logging](#logging)
- [Platform notes](#platform-notes)
- [Known limitations](#known-limitations)
- [Example app](#example-app)
- [Troubleshooting](#troubleshooting)
- [Support](#support)
- [License](#license)

## Requirements

| | |
|---|---|
| Android | 7.0 (API 24) or newer, with Android System WebView or Chrome as the WebView provider (3-D Secure pages) |
| Build | `compileSdk` 34 or newer, Android Gradle Plugin 8.0 or newer, Java 17 toolchain |
| Kotlin | 1.9 or newer (the library is compiled for Kotlin API 2.0 and needs `kotlin-stdlib` 2.0.21) |
| Dependencies | `androidx.activity` 1.9.3+, `androidx.core` 1.13.1+, `kotlinx-coroutines-android` 1.9.0+ (resolved to your versions if newer) |
| Payriff | A publishable key (`pr_pk_live_…`) for your application, and your package name allowed for it |

## Installation

The SDK is published on Maven Central.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.payriff:payriff-android:1.0.0")
}
```

The library manifest is merged into your app automatically. It adds the `INTERNET` permission, the 3-D Secure activity and a small start-up provider (see [Platform notes](#platform-notes)). No ProGuard/R8 rules are required.

## How it works

1. **Your server creates the order** with its secret key: `POST https://api.payriff.com/api/v3/orders` with the header `Authorization: <secret key>`. It returns the order's `orderId` and `clientSecret` (`pr_cs_…`) to the app. The SDK never creates orders and never sees the secret key.
2. **The app configures the SDK** once with its publishable key.
3. **The app shows `PayriffPaymentView`** with the `orderId` and the `clientSecret`. The view loads the order and shows its amount on the Pay button.
4. **The customer enters the card and taps Pay.** The SDK checks that the order is payable by card, encrypts the card, sends it, runs the bank's 3-D Secure step in its sheet and polls the status. The view passes the result to your listener.
5. **Your server fulfils the order** after it has confirmed the payment itself. See [Fulfil on your server](#fulfil-on-your-server).

Every call of the SDK goes to Payriff's Client API (`/api/v3/client/orders/…`) with the publishable key and the order's client secret only.

## Quick start

### 1. Return the order from your backend

```json
{
  "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "clientSecret": "pr_cs_…"
}
```

### 2. Configure the SDK in `Application.onCreate`

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Payriff.configure(
            PayriffConfig(
                publishableKey = "pr_pk_live_…",
                environment = PayriffEnvironment.PRODUCTION,
            ),
        )
    }
}
```

Configure in `Application.onCreate`, not in an activity: if Android kills your process while the customer is in a bank app during 3-D Secure, the SDK resumes the payment and needs the configuration again.

### 3. Show the payment view

```xml
<!-- res/layout/activity_checkout.xml -->
<com.payriff.android.PayriffPaymentView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/paymentView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
class CheckoutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // the view pads itself for the system bars and the keyboard
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_checkout)
        val paymentView = findViewById<PayriffPaymentView>(R.id.paymentView)
        // Set the listener while the activity is created: a result that arrives after the activity was recreated
        // during 3-D Secure goes to the view with the same id, and to this listener.
        paymentView.onResultListener = PayriffCardResultCallback { result -> handle(result) }
        // The orderId and clientSecret your backend returned, passed in by the screen that opened this one.
        paymentView.setOrder(intent.getStringExtra("orderId")!!, intent.getStringExtra("clientSecret")!!)
    }
}
```

Declare the activity with `android:windowSoftInputMode="adjustResize"`, so that the keyboard does not cover the form. The view reads the order when it appears and shows its amount on the Pay button ("Pay 1.02 AZN"). Pay stays disabled until the order has loaded and the card form is complete. See [The payment view](#the-payment-view) for the details, and [Full screen, bottom sheet or dialog](#full-screen-bottom-sheet-or-dialog) for other ways to show it.

### 4. Handle the result

```kotlin
fun handle(result: PayriffResult) {
    when (result) {
        is PayriffResult.Success -> showReceipt(result.payment.orderId)
        is PayriffResult.Declined -> showMessage("The card was declined")
        is PayriffResult.Cancelled -> showMessage("The order was cancelled or has expired")
        is PayriffResult.Pending -> verifyOnBackend(result.payment.orderId)
        is PayriffResult.Failed -> showError(result.error) // Pay works again unless the error ended the order
    }
}
```

Only `Success` means paid, and only for your UI: fulfil the order on your backend, after the backend has confirmed it.

### Java

```java
// Application.onCreate
Payriff.configure(new PayriffConfig.Builder()
        .publishableKey("pr_pk_live_…")
        .environment(PayriffEnvironment.PRODUCTION)
        .build());

// Activity.onCreate
PayriffPaymentView paymentView = findViewById(R.id.paymentView);
paymentView.setOnResultListener(result -> {
    if (result.isSuccess()) {
        String paidOrderId = result.getPayment().getOrderId();
    }
});
paymentView.setOrder(orderId, clientSecret);
```

## Configuration

| `PayriffConfig` | Type | Default | Notes |
|---|---|---|---|
| `publishableKey` | `String?` | `null` | Your publishable key (`pr_pk_live_…`), trimmed. Safe to ship in the app. Missing or not a publishable key: every card call fails with `INVALID_CONFIG`. |
| `environment` | `PayriffEnvironment` | `PRODUCTION` | `PRODUCTION` (`https://api.payriff.com`) or `SANDBOX` (`https://sb-api.payriff.com`, provisional until Payriff confirms the sandbox host) |
| `language` | `PayriffLanguage?` | `null` | Language of the form and the 3-D Secure sheet. `null` uses the device language: `az*` gives `AZ`, `ru*` gives `RU`, anything else `EN`. |
| `enableLogging` | `Boolean` | `false` | See [Logging](#logging) |
| `allowScreenCaptureInDebug` | `Boolean` | `false` | Debug builds only: lets screenshots and screen recordings capture the form and the 3-D Secure sheet, for QA. Release builds ignore it; see [Platform notes](#platform-notes). |

`Payriff.configure` may be called again; the new configuration applies to calls started afterwards. It never throws. A `publishableKey` that is not a publishable key, for example a secret key pasted by mistake, is logged as a warning (`Invalid configuration: …`, when logging is on). `PayriffConfig.toString()` is safe to log: the key appears as `pr_pk_live_***(<length>)`.

Every call needs `Payriff.configure` first; before it, card calls fail with `NOT_CONFIGURED`.

## The payment view

`PayriffPaymentView` is the SDK's ready-made payment screen, a plain `View`. It shows the card form, a Pay button with the order's amount ("Pay 1.02 AZN"), the PCI DSS, Mastercard and Visa badges, a "Proceed securely by PCI DSS" line and a "Powered by Payriff" footer, like the card part of pay.payriff.com.

| `PayriffPaymentView` | |
|---|---|
| `setOrder(orderId, clientSecret)` | The order your server created. Another order resets the view; the same order again changes nothing. |
| `onResultListener` | A `PayriffCardResultCallback` that receives the results on the main thread |
| `style` | A `PayriffPaymentViewStyle`: see [Customise the payment view](#customise-the-payment-view) |
| `language` | The language of the view and its form; `null` uses `PayriffConfig.language`, then the device language |
| `orderId`, `isProcessing` | The order passed to `setOrder`; `true` from Pay until its result, 3-D Secure included |

- **Loading.** The view reads the order with `Payriff.retrieveOrder` when it appears on screen. The Pay button is dimmed and disabled while the order loads, when the order cannot be paid by card or did not load (an error line under the button says why), while the form is incomplete, and while paying, when a spinner replaces its title. If the order does not load for a reason that is not fatal, such as `NETWORK_ERROR`, the view tries again the next time it appears.
- **Paying.** Pay confirms the payment like `PayriffCardPaymentLauncher.confirm`, with a launcher of its own: you need none.
- **Results.** `onResultListener` receives the result of every payment attempt, and once a fatal error that ends the order while it loads (`error.isFatal`, for example an expired client secret). A result that arrives while no listener is set waits until you set one.
- **After a result.** After a result with a status (`Success`, `Declined`, `Cancelled` or `Pending`), the view does not pay the order again. A fatal error and `ORDER_NOT_PAYABLE` end the order as well, and the error line says why. Any other failure keeps the order payable and Pay enabled, so the customer can try again; `error.isRetryable` tells whether that can help (`PAYMENT_FAILED`, `NETWORK_ERROR`). If you close the view after such a failure, show it again later with the same `orderId` and `clientSecret`.
- **Where.** Show it in a `ComponentActivity` (an `AppCompatActivity` or `FragmentActivity` is one), in one of its fragments or dialogs, or in Compose with `AndroidView`. Elsewhere Pay fails with `PRESENTATION_FAILED`. Call `Payriff.configure` before you show it.
- **Recreation.** Give the view an id, and set `onResultListener` while the activity is created. The 3-D Secure sheet's result is registered with the activity under a key made from the view's id, so a result that arrives after the activity was recreated during 3-D Secure goes to the view with the same id. In Compose, declare the id in `res/values/ids.xml` (`<item name="paymentView" type="id" />`) and set it in the factory: `AndroidView(factory = { context -> PayriffPaymentView(context).apply { id = R.id.paymentView } })`.

### Full screen, bottom sheet or dialog

**Full screen.** Give the view the whole screen of an activity, as in the [quick start](#3-show-the-payment-view), or of a fragment. It scrolls when the keyboard is up and keeps the footer at the bottom when there is room. It pads its content for the system bars, the display cutout and the keyboard from the window insets it receives, so it works edge to edge; when a parent already applies and consumes the insets, it adds nothing.

**Bottom sheet or dialog.** In a parent that gives it only the height it needs, such as a bottom sheet or a dialog, the view is as tall as its content and scrolls when the screen is shorter than that. Host it in a `BottomSheetDialogFragment` (Material Components) or in a `DialogFragment`: when the activity is recreated during 3-D Secure, the fragment comes back with the view and its id, so the view receives the result.

```xml
<!-- res/layout/payment_sheet.xml -->
<com.payriff.android.PayriffPaymentView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/paymentView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
/** The payment view in a bottom sheet. For a dialog, extend DialogFragment instead; the rest stays the same. */
class PaymentSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.payment_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val paymentView = view.findViewById<PayriffPaymentView>(R.id.paymentView)
        // Shows the sheet's own background, rounded corners included.
        paymentView.style = PayriffPaymentViewStyle(backgroundColor = Color.TRANSPARENT)
        paymentView.onResultListener = PayriffCardResultCallback { result ->
            (requireActivity() as CheckoutActivity).handle(result)
            dismiss()
        }
        val order = requireArguments()
        paymentView.setOrder(order.getString("orderId")!!, order.getString("clientSecret")!!)
    }
}

// In your AppCompatActivity or FragmentActivity:
PaymentSheet()
    .apply { arguments = bundleOf("orderId" to order.orderId, "clientSecret" to order.clientSecret) }
    .show(supportFragmentManager, "payment")
```

The view paints its own background (`#FCFCFD`, `#222224` in the dark theme). In a sheet or a dialog, set `backgroundColor` to `Color.TRANSPARENT` to show theirs, as above, or give them the same colour.

### Customise the payment view

Set `style` to a `PayriffPaymentViewStyle`. Every property is optional; what you leave out keeps Payriff's default:

```kotlin
paymentView.style = PayriffPaymentViewStyle(
    buttonColor = getColor(R.color.brand),
    buttonCornerRadius = 26f,                             // dp
    buttonHeight = 52f,                                   // dp
    showPoweredBy = false,
    formStyle = PayriffCardFormStyle(
        layout = PayriffCardFormLayout.STACKED,
        focusedBorderColor = getColor(R.color.brand),
        cornerRadius = 14f,
    ),
)
```

| Property | Default |
|---|---|
| `backgroundColor` | `#FCFCFD`; `#222224` in the dark theme |
| `padding` | 20 dp on every side. The view adds the system bars, the display cutout and the keyboard from the window insets it receives. |
| `buttonColor` | Payriff's brand colour `#4DBDC6` |
| `buttonTextStyle` | 16 sp semibold, white. The spinner shown while paying takes its colour. |
| `buttonHeight`, `buttonCornerRadius` | 44 dp (taller with large text) and 16 dp |
| `secondaryTextColor` | `#76787F`: the secure line and the footer, 12 sp regular |
| `showBadges` | `true`: the Mastercard and Visa badges under the button |
| `showSecureNote` | `true`: the lock and "Proceed securely by PCI DSS" |
| `showPoweredBy` | `true`: "Powered by" and the Payriff logo at the bottom |
| `formStyle` | `PayriffCardFormStyle()`: see [Customise the card form](#customise-the-card-form). Its `errorColor` and `errorTextStyle` also style the error line under the Pay button. |

Colours you set are used in both themes. A `PayriffTextStyle` replaces the default text style as a whole, and its `null` colour keeps the default colour. The space between the parts is fixed, as on the hosted page: the button 24 dp below the form, the badges 24 dp below the button, the secure line 20 dp below the badges (24 dp below the button without them) and the footer at least 24 dp below the rest. Changing `style` keeps what the customer typed. From Java, use `new PayriffPaymentViewStyle.Builder()…build()`.

## Build your own screen

`PayriffPaymentView` is built from two public parts that you can use on a screen of your own: `PayriffCardForm` and `PayriffCardPaymentLauncher`. Show the order's amount with [`Payriff.retrieveOrder`](#order-details).

```xml
<com.payriff.android.PayriffCardForm
    android:id="@+id/cardForm"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
class CheckoutActivity : ComponentActivity() {

    // Create it while the activity is created, not in a click handler. The lambda receives a result that arrives
    // after the activity was recreated during 3-D Secure.
    private val cardPayment = PayriffCardPaymentLauncher(this) { result -> handle(result) }

    private fun onPayClicked(order: OrderFromBackend) {
        val form = findViewById<PayriffCardForm>(R.id.cardForm)
        cardPayment.confirm(form, order.orderId, order.clientSecret) { result -> handle(result) }
    }
}
```

`confirm` validates the form (an invalid form returns `Failed(VALIDATION_FAILED)` with `error.fields` and sends nothing), checks that the order is payable by card, encrypts the card, sends it, runs the bank's 3-D Secure step in its sheet and polls the status. There is also a suspending `confirm(form, orderId, clientSecret): PayriffResult`. In Compose, host the form with `AndroidView(factory = { PayriffCardForm(it) })` and create the launcher in the activity. From a fragment, use `PayriffCardPaymentLauncher(fragment, fragment)` while the fragment is created.

From Java:

```java
// Activity: create the launcher while the activity is created
PayriffCardPaymentLauncher cardPayment = new PayriffCardPaymentLauncher(this, result -> handle(result));

cardPayment.confirm(cardForm, orderId, clientSecret, result -> {
    if (result.isSuccess()) {
        String paidOrderId = result.getPayment().getOrderId();
    }
});
```

### The card form

`PayriffCardForm` is a plain `View` with the cardholder name, the card number, expiry (`MM / YY`) and CVV. By default it looks like the card form of Payriff's hosted payment page: the "Card holder" field first, then one "Card information" box with the card number on top and expiry | CVC below it, and one error line under each. `showHolder = false` hides the cardholder name. See [Customise the card form](#customise-the-card-form) to change its look.

- **Validation** (the same rules as Payriff.js, the web SDK): Visa and Mastercard, at most 16 digits (typing stops there), the Luhn check, an expiry that is not in the past and at most 20 years ahead, a 3-digit CVV, and a cardholder name of at most 50 characters without control characters or `<` `>`. Errors appear at the right time: definite errors at once, "incomplete" after leaving the field, "required" after a payment attempt.
- **Brand mark**: the number field shows the Visa or Mastercard mark as soon as the first digits identify the brand, and a neutral card outline before that.
- **Cardholder name**: always upper case, with the locale-independent mapping (`i` → `I`), like the name printed on the card.
- **Options**: `showHolder` (on by default, as on the hosted page; with `false` the field is hidden and the name is sent as `Unknown`, as on the web), `autoAdvance` (number → expiry → CVV, on by default), `language` and `style`. The default colours follow the system light or dark theme.
- **Events**: `onChangeListener` receives `field`, `isValid`, `isEmpty`, `error`, `brand` and `isComplete`; never the typed values. `isComplete`, `clear()` and `focus(field)` are available too.
- **Security**: numeric keyboards; suggestions, autocorrection and keyboard learning off; the CVV is masked; copy and cut are blocked on the number and CVV (paste into the number works); autofill is offered only for number, expiry and name. The form never saves what was typed in the instance state, clears it after a final result and when it leaves the window, and sets `FLAG_SECURE` on its window while attached.

The form is locked while a payment confirmed with it runs (`isProcessing`).

### Customise the card form

Set `style` to a `PayriffCardFormStyle`, on a `PayriffCardForm` or through `PayriffPaymentViewStyle.formStyle`. Every property is optional; what you leave out keeps Payriff's default:

```kotlin
cardForm.style = PayriffCardFormStyle(
    cornerRadius = 16f,                                   // dp
    borderColor = getColor(R.color.form_border),
    focusedBorderColor = getColor(R.color.brand),
    labelTextStyle = PayriffTextStyle(size = 13f, weight = PayriffTextStyle.BOLD),
    holderLabel = "Name on card",
)
```

| Property | Default |
|---|---|
| `layout` | `GROUPED`: the cardholder name, then one card box. `STACKED`: every field separate, with its own label and error. |
| `labelTextStyle` | 14 sp semibold, `#34363A` (`#E1E4E8` in the dark theme) |
| `inputTextStyle` | 14 sp regular, `#4D4F54` (`#8E8E93` in the dark theme); the placeholders take its size |
| `placeholderColor` | `#76787F` (`#5F6167` in the dark theme) |
| `errorTextStyle` | 13 sp regular, in `errorColor` |
| `showLabels`, `holderLabel`, `cardLabel` | `true`; `null` uses the form's language ("Card holder", "Card information"). Without labels, accessibility services still read each field's label. |
| `fieldBackgroundColor` | transparent: the fields show the background behind the form |
| `borderColor` | `#ECEEF0` (`#3F4145` in the dark theme) |
| `focusedBorderColor` | Payriff's brand colour `#4DBDC6` |
| `errorColor` | `#EC4D3D` (`#EC5545` in the dark theme) |
| `borderWidth`, `cornerRadius` | 1 and 12 dp |
| `holderHeight`, `rowHeight` | 46 and 40 dp, outline included; larger text makes them taller. The card box with its two rows is 79 dp tall. |
| `horizontalPadding`, `labelSpacing`, `sectionSpacing` | 14, 10 and 16 dp |

`PayriffTextStyle` has `size` (sp), `weight`, `color` and `typeface`. It replaces the default text style as a whole, and its `null` colour keeps the default colour. A section's outline turns `errorColor` while it shows an error, and `focusedBorderColor` while one of its fields has the focus. Colours you set are used in both themes. Changing `style` keeps what the customer typed. From Java, use `new PayriffCardFormStyle.Builder()…build()`.

## Order details

`Payriff.retrieveOrder(orderId, clientSecret)` loads the order through the Client API, for example to show its amount next to your own form (the payment view loads it itself):

```kotlin
val order: PayriffClientOrder = Payriff.retrieveOrder(orderId, clientSecret)
// order.amount, order.currency, order.description, order.merchantName, order.status, order.isPayableByCard
```

It is a `suspend` function that throws `PayriffError` (15 s timeout; HTTP 429 is retried after 1 s, 2 s and 4 s). The order id is trimmed and sent as one percent-encoded path segment; a blank id or a malformed client secret fails with `INVALID_ORDER`. Java callers use `Payriff.retrieveOrder(orderId, clientSecret, PayriffCallback)`, which calls back on the main thread and returns a `PayriffCancellable`.

`confirm` loads the order itself as well and fails with `ORDER_NOT_PAYABLE` unless its status is `CREATED` or `PENDING` and `CARD` is one of its payment methods.

## Handling the result

| Variant | Payload | When |
|---|---|---|
| `Success` | `payment` | Status `APPROVED` or `PREAUTH_APPROVED`. The only paid outcome. |
| `Declined` | `payment` | Status `DECLINED` |
| `Cancelled` | `payment`, `reason` | `CANCELED` or `EXPIRED` (also `PREAUTH_EXPIRED`) |
| `Pending` | `payment`, `reason` | `NOT_FINAL`: the status was not final when polling ended (120 s), or the customer closed the 3-D Secure sheet. `UNEXPECTED_STATUS`: a final status such as `REFUNDED`. Verify on your backend. |
| `Failed` | `error` | The payment could not start or run: see [Errors](#errors) |

Once the card was sent, closing the 3-D Secure sheet asks for confirmation and checks the status once. It returns that status when it is final, and `Pending(NOT_FINAL)` otherwise, never `Cancelled`: the card was already submitted.

`PayriffPayment` carries `orderId`, `status` (`PayriffPaymentStatus`), `rawStatus` (Payriff's exact string), and `amount` and `currency` from the last status answer. `transactionId` is part of the result shape shared with the other Payriff SDKs and is `null` in the card flow.

Every result has `isSuccess`, `payment` (`null` for `Failed`) and `error` (non-null only for `Failed`), and serializes to a JSON shape shared with the other Payriff SDKs. Every key is present, and unknown values are `null`:

```kotlin
result.toJson()
// {"type":"success","payment":{"orderId":"o1","status":"APPROVED","rawStatus":"APPROVED","amount":10.5,"currency":"AZN","transactionId":null}}
// {"type":"pending","reason":"notFinal","payment":{"orderId":"o1","status":"PENDING","rawStatus":"PENDING","amount":null,"currency":null,"transactionId":null}}
// {"type":"failed","error":{"code":"PAYMENT_FAILED","message":"Insufficient funds","isRetryable":true,"httpStatus":402,"payriffCode":"15002","supportTicket":null}}
```

Exactly one result is delivered per confirm, also after rotation or process death during 3-D Secure.

## Fulfil on your server

The result the SDK returns is for your app's UI only. It is worked out on the customer's device, where the device owner can tamper with it, so it must never be the reason your backend ships goods or credits an account.

- **Fulfil only after your backend has confirmed the order itself**: with `GET /api/v3/orders/{orderId}` and the secret key, or when Payriff's callback reaches your `callbackUrl`. Never trust a callback's content on its own: on every callback, re-fetch the order.
- **Check the order against your own record**: the `orderId` must be an order your backend created for this cart, and `amount` and `currency` must match it.
- **`Pending` is not proof that nothing was paid.** Before you let the customer pay the same cart again, reconcile it on your backend.
- **Treat ids from the app as untrusted input**: encode them in your URLs and check that the order belongs to the signed-in customer.

## Errors

`PayriffError` has `code`, `message`, `isRetryable`, `httpStatus`, `payriffCode` (Payriff's envelope code), `supportTicket` (parsed from Payriff's message), `isFatal` and `fields`.

`isFatal` means "this order can no longer be paid from the app": create a new order (and a new `clientSecret`) before trying again.

| `code` | When | Retryable | Fatal |
|---|---|---|---|
| `VALIDATION_FAILED` | The form is not filled in correctly. `error.fields` lists each field and its error; no request was made. | no | no |
| `ORDER_NOT_PAYABLE` | The order's status is not `CREATED` or `PENDING`, `CARD` is not one of its payment methods, or Payriff answered HTTP 400 | no | no |
| `INVALID_REQUEST` | Payriff rejected the request format (HTTP 400, code `15400`): an integration bug | no | no |
| `PAYMENT_FAILED` | Payriff did not accept the payment; carries Payriff's message | yes: the customer may try again | no |
| `SESSION_EXPIRED` | HTTP 404: the order does not exist, or its client secret is wrong or expired | no | yes |
| `UNAUTHORIZED` | HTTP 401: the publishable key is wrong or revoked, or your package name is not allowed for it | no | yes |
| `RATE_LIMITED` | HTTP 429, after 3 retries (1 s, 2 s, 4 s) | no | yes |
| `UNEXPECTED_RESPONSE` | Payriff answered with a next step the SDK cannot run, and the status was not final | no | no |
| `NETWORK_ERROR` | No HTTP response: offline, DNS, TLS or timeout (a `/pay` was already retried once) | yes | no |
| `NOT_CONFIGURED` | `Payriff.configure` was never called | no | no |
| `INVALID_CONFIG` | `publishableKey` is missing or not a publishable key | no | no |
| `INVALID_ORDER` | A blank `orderId`, or a `clientSecret` that is not a `pr_cs_…` value | no | no |
| `PAYMENT_IN_PROGRESS` | Another card payment of this SDK is running | no | no |
| `PRESENTATION_FAILED` | The 3-D Secure sheet cannot be shown, for example Android System WebView is missing | no | no |
| `UNKNOWN` | Anything else, for example a card that could not be encrypted on this device | never | no |

`API_ERROR` is part of the error codes shared with the other Payriff SDKs; the card flow does not return it.

## Helpers

| Helper | Android |
|---|---|
| SDK version | `Payriff.version` (also the constant `Payriff.VERSION`) |
| A card payment is running | `Payriff.isPaymentInProgress` |
| API base URL | `PayriffEnvironment.apiBaseUrl`, for example `https://sb-api.payriff.com` |
| Status parser | `PayriffPaymentStatus.fromRaw(raw)` (aliases such as `PAID` give `APPROVED`) |
| Result helpers | `result.isSuccess`, `result.payment`, `result.error` |
| Result JSON | `result.toJson()` |

## Logging

Logging is off by default. With `enableLogging = true` the SDK writes lines such as `[Payriff] [INFO] …` (levels `DEBUG`, `INFO`, `WARNING`, `ERROR`) to Logcat (tag `Payriff`) and to a listener:

```kotlin
PayriffLogger.listener = PayriffLogListener { entry ->
    console.append(entry.formatted) // "[Payriff] [LEVEL] message"
    // entry.level, entry.message, entry.timestampMillis (epoch milliseconds)
}
```

In debuggable apps, each Client API call also logs its request and response as pretty-printed JSON at `DEBUG` level. Release builds never log them.

Privacy rules:

- Card data is never logged. Keys, client secrets, the encrypted card, the wrapped key and 3-D Secure form values appear as `***(<length>)`, the publishable key as `pr_pk_live_***(<length>)`, and card numbers in a response as their last 4 digits.
- `http(s)` URLs are logged without their query and fragment; any other URL only as `<scheme>:…`.
- The SDK never reads the content of the bank's pages.

## Platform notes

- **The 3-D Secure sheet** is a translucent activity owned by the SDK: about 94% of the screen height, up to 640 dp wide on tablets, following the system light or dark theme. Its header shows a drag handle, the title ("Payment" in the payment language) and a close button; there is no address, padlock or progress bar. A native loader covers the page until it first appears, and later page loads show a small spinner in the header. The bank page cannot be zoomed, has no overscroll effect or scrollbars, and is inset 16 dp from the sides and 12 dp from the top and bottom on a white backdrop. The 3DS Method step runs in invisible WebViews under the loader.
- **Closing.** The close (✕) button, the back gesture, a tap on the dimmed area and dragging the header down all ask the customer to confirm, then check the status once. While the SDK is checking the payment ("Checking payment…"), the ✕ is disabled and dimmed.
- **Rotation and theme changes** do not reload the bank page.
- **Process death.** If Android recreates your process while the 3-D Secure sheet is open, the sheet checks the status once and returns the result to the launcher of the recreated activity (its `onRestoredResult` callback), or to the payment view with the same id. The sheet keeps only the order id and its client secret; card data is never saved. This is why `Payriff.configure` belongs in `Application.onCreate`.
- **Bank apps.** Links to other apps on a bank page (`intent:` URLs, bank schemes) are opened with hardened intents: browsable only, no explicit component or selector, and no URI permission grants. Links with `file:` or `content:` data, and links longer than 64 KB, are refused; an `intent:` fallback loads only over https. A malformed link or a refused launch is logged and ignored; it never crashes your app.
- **One payment at a time.** A confirm while another card payment runs returns `Failed(PAYMENT_IN_PROGRESS)`.
- **Screen protection.** The form's window (while it is attached), the 3-D Secure sheet and its close confirmation use `FLAG_SECURE`: screenshots, screen recordings, screen sharing and the recents thumbnail show nothing of them. On Android 14 and newer the bank page is also marked as sensitive for accessibility, so only accessibility tools such as TalkBack can read it. For QA screenshots, a debuggable build can turn both off with `PayriffConfig(allowScreenCaptureInDebug = true)`; release builds always protect them. Session-replay and screenshot tools that run inside your own app are not stopped by `FLAG_SECURE`: exclude the card form from them.
- **App identity.** Every Client API request carries your package name as `X-Client-App-Id`, and never `Origin` or `Authorization`. Payriff allows your publishable key only for the package names you registered.
- **Start-up provider.** The SDK's manifest adds a small content provider (`com.payriff.android.internal.PayriffInitProvider`). When your process starts, it records your package name and whether the app is debuggable, so `Payriff.retrieveOrder` works without a `Context`. It is not exported, stores nothing and does no I/O. If you remove it from your merged manifest (`tools:node="remove"`), the SDK learns this at the first card payment instead and, until then, treats the app as a release build.
- **Security.** The 3-D Secure activity is not exported, never injects JavaScript, does not change the user agent, never proceeds on TLS errors, blocks plain-http pages, and keeps third-party cookies on only for its own WebViews (3-D Secure needs them).

## Known limitations

These are documented platform limits, not bugs.

| Limitation | Consequence or mitigation |
|---|---|
| The sandbox host (`https://sb-api.payriff.com`) is provisional. | It is one constant in the SDK; it changes if Payriff confirms another host. |
| The renderer-crash callback (`onRenderProcessGone`) exists only on Android 8.0 (API 26) and newer. | On API 24 and 25 a WebView renderer crash during 3-D Secure ends the app process. The process-death restore then applies: one status check. |

## Example app

The `sample` module (application id `com.payriff.example.android`) has one main screen, **Card (native)**:

- **Order from your backend**: `orderId` and `clientSecret` fields and **Paste order**, which reads the JSON `{"orderId", "clientSecret"}` from the clipboard, or the two values separated by white space. The screen then shows the order through `Payriff.retrieveOrder`: merchant, status, amount and description.
- **Pay with PayriffPaymentView** opens the order in the SDK's payment view, full screen under a back button (`PaymentActivity`). That screen closes with the view's first result.
- Below it, the same payment on a screen built from the SDK's parts: **the card form** with the cardholder name (a checkbox hides it) and **Pay**, which calls `PayriffCardPaymentLauncher.confirm`. **Form style** switches the form between Payriff's default look and a custom `PayriffCardFormStyle`.
- The outcome of either payment opens in a result dialog with the status, the amount, the order id and the result JSON. Any result but a failure clears the order: an order can be paid once.
- **Settings**: publishable key, environment and language; switches for logging and for screenshots in debug builds; and the look of the payment view: **Custom style** (a custom `PayriffPaymentViewStyle`), or the default look without the card form labels, the card badges, the secure line or "Powered by Payriff".
- **SDK log**: a live console that renders `entry.formatted`.

The example holds no secret key and creates no orders: your server creates the order with its secret key (`POST /api/v3/orders`) and returns `orderId` and `clientSecret`. Payriff accepts the publishable key only from the package names you registered for it, so ask Payriff to allow `com.payriff.example.android`. The example's defaults come from `local.properties` in the repository root, which is git-ignored (start from `local.properties.example`), and are compiled into the debug build only:

```properties
payriff.publishableKey=
payriff.environment=SANDBOX
```

Values entered in the Settings screen override them and are kept in the app's private storage (backups are disabled).

```bash
./gradlew :sample:installDebug
```

## Troubleshooting

| Symptom | What to check |
|---|---|
| `Failed(NOT_CONFIGURED)` | Call `Payriff.configure` in `Application.onCreate`. |
| `Failed(INVALID_CONFIG)` | Set `publishableKey` to your publishable key (`pr_pk_live_…`), never the secret key. |
| `Failed(UNAUTHORIZED)` | The publishable key does not belong to the selected environment, was revoked, or your package name is not allowed for it. |
| `Failed(SESSION_EXPIRED)` | The order does not exist in this environment, or its `clientSecret` is wrong or expired. Create a new order. |
| `Failed(ORDER_NOT_PAYABLE)` | The order was already paid, cancelled or expired, or card payments are not enabled for it. |
| `Failed(VALIDATION_FAILED)` | The form shows what to fix under each field; `error.fields` lists the same. |
| `Failed(PRESENTATION_FAILED)` | The payment view is not shown in a `ComponentActivity` (for example an `AppCompatActivity`) or in one of its fragments or dialogs, or Android System WebView is missing or disabled. |
| Pay stays disabled in the payment view | The order is still loading, cannot be paid by card or did not load (the error line under the button says why), or the card form is not complete yet. |
| `Pending(NOT_FINAL)` | The status was not final in time, or the customer closed the 3-D Secure sheet. Confirm it on your backend; Payriff's callback will arrive there. |
| Screenshots or screen recordings of the form are black | That is the screen protection (`FLAG_SECURE`). For QA in a debug build, set `allowScreenCaptureInDebug = true`. |
| `IllegalStateException: LifecycleOwner … is attempting to register while current state is RESUMED` | Create `PayriffCardPaymentLauncher` while the activity or fragment is created, not in a click handler. |
| The result never arrives after rotating or leaving the app | Create the launcher while the activity is created and pass an `onRestoredResult` callback to it. For the payment view: give it an id and set `onResultListener` while the activity is created. |
| The sheet shows "The page couldn't be loaded" | A network or TLS error on the bank page. "Try again" reloads it; check connectivity and that Android System WebView is up to date. |

## Support

- Documentation: [docs.payriff.com](https://docs.payriff.com)
- Issues: [github.com/PayRiff/payriff-android/issues](https://github.com/PayRiff/payriff-android/issues)
- Security: see [SECURITY.md](SECURITY.md). Report vulnerabilities privately, never in a public issue.

## License

The Payriff SDK License, the same terms as Payriff.js: you may include the SDK
unmodified in your app to accept payments through Payriff. See
[LICENSE](LICENSE) for the full text.
