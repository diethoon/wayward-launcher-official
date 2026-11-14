package com.wayward.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

/** Neutral translucent-glass surface with raised and persistently inset states. */
final class GlassButtonStyle {
    private GlassButtonStyle() {}

    static void bind(Context context, View view, boolean recessed, float cornerRadiusDp) {
        apply(context, view, recessed, cornerRadiusDp);
        view.setOnTouchListener((target, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                apply(context, target, true, cornerRadiusDp);
                target.animate().scaleX(0.99f).scaleY(0.99f).setDuration(80).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                target.animate().scaleX(1f).scaleY(1f).setDuration(125).start();
                apply(context, target, recessed, cornerRadiusDp);
            }
            return false;
        });
    }

    private static void apply(Context context, View view, boolean recessed, float cornerRadiusDp) {
        view.setBackground(surface(context, recessed, cornerRadiusDp));
        view.setElevation(dp(context, recessed ? 0.8f : 5.5f));
        view.setTranslationY(recessed ? dp(context, 1f) : 0f);
        view.setClipToOutline(false);
        if (Build.VERSION.SDK_INT >= 21) view.setStateListAnimator(null);
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(Color.rgb(132, 139, 145));
            view.setOutlineSpotShadowColor(Color.rgb(116, 124, 131));
        }
    }

    private static Drawable surface(Context context, boolean recessed, float cornerRadiusDp) {
        int[] bodyColors = recessed
                ? new int[]{
                    Color.argb(232, 224, 227, 230),
                    Color.argb(218, 239, 241, 243)
                }
                : new int[]{
                    Color.argb(246, 255, 255, 255),
                    Color.argb(202, 247, 248, 249)
                };
        GradientDrawable body = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, bodyColors);
        body.setCornerRadius(dp(context, cornerRadiusDp));
        body.setStroke(dp(context, 1f), recessed
                ? Color.argb(145, 122, 130, 137)
                : Color.argb(245, 255, 255, 255));

        GradientDrawable outerRim = new GradientDrawable();
        outerRim.setColor(Color.TRANSPARENT);
        outerRim.setCornerRadius(dp(context, Math.max(1f, cornerRadiusDp - 1.5f)));
        outerRim.setStroke(dp(context, 1f), recessed
                ? Color.argb(95, 104, 112, 119)
                : Color.argb(75, 145, 153, 160));

        GradientDrawable innerHighlight = new GradientDrawable();
        innerHighlight.setColor(Color.TRANSPARENT);
        innerHighlight.setCornerRadius(dp(context, Math.max(1f, cornerRadiusDp - 3.5f)));
        innerHighlight.setStroke(dp(context, 1f), recessed
                ? Color.argb(205, 255, 255, 255)
                : Color.argb(190, 255, 255, 255));

        GradientDrawable topSheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                    Color.argb(recessed ? 34 : 128, 255, 255, 255),
                    Color.argb(recessed ? 8 : 28, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                });
        topSheen.setCornerRadius(dp(context, Math.max(1f, cornerRadiusDp - 4f)));

        LayerDrawable layers = new LayerDrawable(
                new Drawable[]{body, outerRim, innerHighlight, topSheen});
        int rimInset = dp(context, 1.5f);
        layers.setLayerInset(1, rimInset, rimInset, rimInset, rimInset);
        int highlightInset = dp(context, 3f);
        layers.setLayerInset(2, highlightInset, highlightInset, highlightInset, highlightInset);
        int sheenInset = dp(context, 4f);
        layers.setLayerInset(3, sheenInset, sheenInset, sheenInset, sheenInset);
        return layers;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
