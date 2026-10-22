package com.payriff.example.android;

import androidx.activity.ComponentActivity;

import com.payriff.android.Payriff;
import com.payriff.android.PayriffCallback;
import com.payriff.android.PayriffCancellable;
import com.payriff.android.PayriffCardForm;
import com.payriff.android.PayriffCardFormLayout;
import com.payriff.android.PayriffCardFormStyle;
import com.payriff.android.PayriffCardPaymentLauncher;
import com.payriff.android.PayriffClientOrder;
import com.payriff.android.PayriffConfig;
import com.payriff.android.PayriffEnvironment;
import com.payriff.android.PayriffError;
import com.payriff.android.PayriffLanguage;
import com.payriff.android.PayriffLogger;
import com.payriff.android.PayriffPayment;
import com.payriff.android.PayriffPaymentStatus;
import com.payriff.android.PayriffPaymentView;
import com.payriff.android.PayriffPaymentViewStyle;
import com.payriff.android.PayriffResult;
import com.payriff.android.PayriffTextStyle;

/**
 * How the SDK looks from Java. The example app itself is written in Kotlin; this class is compiled with it to
 * keep the Java API (builders, static methods, callbacks) usable. It is not called at runtime.
 */
final class JavaInterop {

    private JavaInterop() {
    }

    static void configure() {
        PayriffConfig config = new PayriffConfig.Builder()
                .publishableKey("pr_pk_live_your_key")
                .environment(PayriffEnvironment.SANDBOX)
                .language(PayriffLanguage.AZ)
                .enableLogging(true)
                .allowScreenCaptureInDebug(false)
                .build();
        Payriff.configure(config);
    }

    /** The helpers shared by every Payriff SDK, as Java sees them. */
    static String describeSdk() {
        PayriffLogger.setListener(entry -> android.util.Log.d("JavaInterop", entry.getFormatted() + " @" + entry.getTimestampMillis()));
        return "Payriff " + Payriff.getVersion() + " (" + Payriff.VERSION + ")"
                + ", in progress: " + Payriff.isPaymentInProgress()
                + ", sandbox API: " + PayriffEnvironment.SANDBOX.getApiBaseUrl()
                + ", PAID is " + PayriffPaymentStatus.fromRaw("PAID");
    }

    /** The ready-made payment view: its look, its listener (set it in onCreate) and the order your backend created. */
    static void showPaymentView(PayriffPaymentView paymentView, String orderId, String clientSecret) {
        paymentView.setStyle(new PayriffPaymentViewStyle.Builder()
                .buttonColor(0xFF6C47FF)
                .showPoweredBy(false)
                .formStyle(new PayriffCardFormStyle.Builder().focusedBorderColor(0xFF6C47FF).build())
                .build());
        paymentView.setOnResultListener(result -> {
            if (result.isSuccess()) {
                PayriffPayment payment = result.getPayment();
                android.util.Log.i("JavaInterop", "Paid order " + (payment != null ? payment.getOrderId() : "?"));
            }
        });
        paymentView.setOrder(orderId, clientSecret);
    }

    /** Create the launcher in onCreate; the callback receives a result that arrives after a recreation. */
    static PayriffCardPaymentLauncher registerCard(ComponentActivity activity) {
        return new PayriffCardPaymentLauncher(activity, result -> android.util.Log.i("JavaInterop", "restored " + result.toJson()));
    }

    /** The card form's look: start from Payriff's default and change what you need. */
    static void styleCardForm(PayriffCardForm form) {
        form.setStyle(new PayriffCardFormStyle.Builder()
                .layout(PayriffCardFormLayout.GROUPED)
                .labelTextStyle(new PayriffTextStyle(13f, PayriffTextStyle.BOLD))
                .focusedBorderColor(0xFF6C47FF)
                .cornerRadius(16f)
                .build());
    }

    /** Pays the order your backend created with the card in the form. */
    static void payByCard(PayriffCardPaymentLauncher launcher, PayriffCardForm form, String orderId, String clientSecret) {
        form.setShowHolder(true);
        launcher.confirm(form, orderId, clientSecret, result -> {
            if (result.isSuccess()) {
                PayriffPayment payment = result.getPayment();
                android.util.Log.i("JavaInterop", "Paid order " + (payment != null ? payment.getOrderId() : "?"));
            } else if (result instanceof PayriffResult.Failed) {
                PayriffError error = ((PayriffResult.Failed) result).getError();
                if (error.isFatal()) {
                    android.util.Log.w("JavaInterop", "Create a new order: " + error.getCode());
                } else {
                    android.util.Log.w("JavaInterop", error.getCode() + " " + error.getFields().size() + " field errors");
                }
            }
            android.util.Log.d("JavaInterop", result.toJson());
        });
    }

    static PayriffCancellable retrieve(String orderId, String clientSecret) {
        return Payriff.retrieveOrder(orderId, clientSecret, new PayriffCallback<PayriffClientOrder>() {
            @Override
            public void onSuccess(PayriffClientOrder value) {
                android.util.Log.i("JavaInterop", value.getOrderId() + " payable by card: " + value.isPayableByCard());
            }

            @Override
            public void onError(PayriffError error) {
                android.util.Log.w("JavaInterop", error.getCode().name());
            }
        });
    }
}
