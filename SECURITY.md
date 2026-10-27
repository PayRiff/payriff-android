# Security policy

## Reporting a vulnerability

Report security problems privately. Do not open a public issue, pull request or discussion for them: a public report
shows the problem to everyone before a fix is available.

1. Open the **Security** tab of this repository on GitHub.
2. Choose **Report a vulnerability**.
3. Fill in the form and submit it.

This uses GitHub's private vulnerability reporting: only you and the maintainers of this repository can see the report,
and you can follow it and answer questions there. Please give us time to fix the problem before you share it with
anyone else.

For bugs that are not security problems, use the [issue tracker](https://github.com/PayRiff/payriff-android/issues).

## Supported versions

| Version | Supported |
|---|---|
| The latest 1.x release | Yes |
| Older releases | No. Update to the latest 1.x release first, and check whether the problem is still there. |

## What to include

- The SDK version (`Payriff.version`), the Android version and the device or emulator.
- What the problem lets an attacker do, and what the attacker needs for it: for example another app on the same device,
  a rooted device, or a position on the network.
- Steps to reproduce, ideally with a small app or a unit test.
- Logs, screenshots or a proof of concept that show the problem.

Use the sandbox environment of region `AZ`, sandbox orders and test cards. Region `SA` has no sandbox: its `SANDBOX`
payments go to production and charge real cards. Never send real card numbers, live keys, client secrets or customer
data, and remove them from logs and screenshots before you attach them.
