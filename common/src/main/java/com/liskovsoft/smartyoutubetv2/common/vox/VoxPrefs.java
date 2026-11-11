package com.liskovsoft.smartyoutubetv2.common.vox;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

public class VoxPrefs {
    private static final String PREFS = "vox_prefs";
    private static final String OAUTH_TOKEN = "oauth_token";
    private static final String LIVELY = "lively_enabled";
    private static final String ORIGINAL_VOLUME = "original_volume";
    private final SharedPreferences mPrefs;

    private VoxPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static VoxPrefs instance(Context context) {
        return new VoxPrefs(context);
    }

    @Nullable
    public String getOAuthToken() {
        return mPrefs.getString(OAUTH_TOKEN, null);
    }

    public void setOAuthToken(@Nullable String token) {
        mPrefs.edit().putString(OAUTH_TOKEN, token).apply();
    }

    public boolean isLivelyEnabled() {
        return mPrefs.getBoolean(LIVELY, true);
    }

    public void setLivelyEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(LIVELY, enabled).apply();
    }

    /** Lively voices need a signed-in Yandex account. */
    public boolean useLively() {
        String token = getOAuthToken();
        return isLivelyEnabled() && token != null && !token.isEmpty();
    }

    public float getOriginalVolume() {
        return mPrefs.getFloat(ORIGINAL_VOLUME, 0.15f);
    }

    public void setOriginalVolume(float volume) {
        mPrefs.edit().putFloat(ORIGINAL_VOLUME, volume).apply();
    }
}
