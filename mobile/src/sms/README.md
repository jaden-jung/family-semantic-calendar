# Android SMS Ingestion

The Expo skeleton intentionally keeps SMS permissions disabled. Google Play treats SMS access as sensitive, and Expo managed apps do not expose inbox listeners directly.

Recommended next step for Android-first private/family use:

1. Run `npx expo prebuild --platform android`.
2. Add a native Android `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED`.
3. Request `RECEIVE_SMS` and optionally `READ_SMS`.
4. Parse card approval messages on-device.
5. POST matched messages to `/sms/card-payments`.

For Play Store distribution, consider alternative ingestion paths such as notification listener, card-company email forwarding, or explicit user import because SMS permission approval is restrictive.

