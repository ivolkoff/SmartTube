package com.liskovsoft.smartyoutubetv2.common.vox;

import com.liskovsoft.smartyoutubetv2.common.BuildConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Yandex OAuth device flow (ya.ru/device) with the user's own OAuth app.
 * Token exchange needs the client secret, so it comes from local.properties, never from git.
 */
public class VoxYandexAuth {
    private static final String DEVICE_CODE_URL = "https://oauth.yandex.ru/device/code";
    private static final String TOKEN_URL = "https://oauth.yandex.ru/token";
    private static final OkHttpClient sClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    public static class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final int intervalSec;
        public final int expiresInSec;

        DeviceCode(JSONObject json) {
            deviceCode = json.optString("device_code");
            userCode = json.optString("user_code");
            verificationUrl = json.optString("verification_url", "https://ya.ru/device");
            intervalSec = Math.max(1, json.optInt("interval", 5));
            expiresInSec = json.optInt("expires_in", 300);
        }
    }

    public static boolean isConfigured() {
        return !BuildConfig.VOX_YANDEX_CLIENT_ID.isEmpty() && !BuildConfig.VOX_YANDEX_CLIENT_SECRET.isEmpty();
    }

    public static Observable<DeviceCode> requestDeviceCode() {
        return Observable.fromCallable(() -> {
            JSONObject json = post(DEVICE_CODE_URL, new FormBody.Builder()
                    .add("client_id", BuildConfig.VOX_YANDEX_CLIENT_ID)
                    .build());
            if (json.has("error")) {
                throw new IOException(json.optString("error_description", json.optString("error")));
            }
            return new DeviceCode(json);
        }).subscribeOn(Schedulers.io());
    }

    /** Emits the access token once the user confirms the code on another device. */
    public static Observable<String> pollToken(DeviceCode code) {
        return Observable.interval(code.intervalSec, code.intervalSec, TimeUnit.SECONDS, Schedulers.io())
                .take(Math.max(1, code.expiresInSec / code.intervalSec))
                .map(tick -> post(TOKEN_URL, new FormBody.Builder()
                        .add("grant_type", "device_code")
                        .add("code", code.deviceCode)
                        .add("client_id", BuildConfig.VOX_YANDEX_CLIENT_ID)
                        .add("client_secret", BuildConfig.VOX_YANDEX_CLIENT_SECRET)
                        .build()))
                .filter(json -> {
                    String error = json.optString("error");
                    if ("authorization_pending".equals(error)) {
                        return false;
                    }
                    if (!error.isEmpty()) {
                        throw new IOException(json.optString("error_description", error));
                    }
                    return true;
                })
                .map(json -> json.getString("access_token"))
                .take(1);
    }

    private static JSONObject post(String url, FormBody body) throws Exception {
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = sClient.newCall(request).execute()) {
            // Yandex returns JSON errors with HTTP 400, so the body is parsed regardless of the code
            String text = response.body() != null ? response.body().string() : "";
            return new JSONObject(text.isEmpty() ? "{}" : text);
        }
    }
}
