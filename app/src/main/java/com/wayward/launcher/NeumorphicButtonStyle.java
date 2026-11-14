package com.wayward.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

/** Shared soft-UI button surface for raised, pressed, and persistently selected controls. */
final class NeumorphicButtonStyle {
    private NeumorphicButtonStyle() {}

    static void bind(Context context, View view, boolean recessed, float cornerRadiusDp) {
        apply(context, view, recessed, cornerRadiusDp);
        view.setOnTouchListener((target, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                apply(context, target, true, cornerRadiusDp);
                target.animate().scaleX(0.985f).scaleY(0.985f).setDuration(75).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                target.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                apply(context, target, recessed, cornerRadiusDp);
            }
            return false;
        });
    }

    private static void apply(Context context, View view, boolean recessed, float cornerRadiusDp) {
        view.setBackground(surface(context, recessed, cornerRadiusDp));
        view.setElevation(dp(context, recessed ? 1.5f : 7f));
        view.setTranslationY(recessed ? dp(context, 1.2f) : 0f);
        view.setClipToOutline(false);
        if (Build.VERSION.SDK_INT >= 21) {
            view.setStateListAnimator(null);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(recessed
                    ? Color.rgb(119, 134, 151) : Color.rgb(113, 128, 145));
            view.setOutlineSpotShadowColor(recessed
                    ? Color.rgb(151, 164, 180) : Color.rgb(105, 120, 137));
        }
    }

    private static Drawable surface(Context context, boolean recessed, float cornerRadiusDp) {
        int[] colors = recessed
                ? new int[]{Color.rgb(218, 227, 236), Color.rgb(243, 247, 251)}
                : new int[]{Color.rgb(253, 254, 255), Color.rgb(222, 232, 242)};
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        body.setCornerRadius(dp(context, cornerRadiusDp));
        body.setStroke(dp(context, 1f), recessed
                ? Color.rgb(174, 190, 207) : Color.rgb(255, 255, 255));

        GradientDrawable edge = new GradientDrawable();
        edge.setColor(Color.TRANSPARENT);
        edge.setCornerRadius(dp(context, Math.max(2f, cornerRadiusDp - 2f)));
        edge.setStroke(dp(context, 1f), recessed
                ? Color.argb(205, 255, 255, 255) : Color.argb(155, 255, 255, 255));

        GradientDrawable innerEdge = new GradientDrawable();
        innerEdge.setColor(Color.TRANSPARENT);
        innerEdge.setCornerRadius(dp(context, Math.max(1f, cornerRadiusDp - 4f)));
        innerEdge.setStroke(dp(context, 1f), recessed
                ? Color.argb(95, 128, 145, 164) : Color.argb(65, 255, 255, 255));

        LayerDrawable layers = new LayerDrawable(new Drawable[]{body, edge, innerEdge});
        int inset = dp(context, 2f);
        layers.setLayerInset(1, inset, inset, inset, inset);
        int innerInset = dp(context, 3.5f);
        layers.setLayerInset(2, innerInset, innerInset, innerInset, innerInset);
        return layers;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
