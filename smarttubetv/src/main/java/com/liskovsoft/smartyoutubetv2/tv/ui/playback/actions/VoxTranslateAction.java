package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

public class VoxTranslateAction extends TwoStateAction {
    public VoxTranslateAction(Context context) {
        super(context, R.id.action_vox_translate, R.drawable.action_vox_translate);

        String label = context.getString(R.string.vox_translate);
        setLabels(new String[] {label, label});
    }
}
