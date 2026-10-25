package net.programmierecke.radiodroid2.station;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.PreferenceManager;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.Utils;

/**
 * Radio fork: station logo look in lists and the small player.
 * <ul>
 * <li>No logo / loading / failed: the skin's surface colour with a tinted image icon (not a
 *   white square).</li>
 * <li>Real logos are dimmed slightly in dark themes ("Dim station logos in dark themes",
 *   on by default), so white logo backgrounds do not glare.</li>
 * </ul>
 */
public final class StationIcons {
    public static final String PREF_DIM = "dim_station_logos";
    /** RGB scale for dimmed logos (~25 % darker, slightly less contrast to white). */
    private static final float DIM = 0.75f;

    private StationIcons() { }

    private static int themeColor(Context ctx, int attr, int fallback) {
        TypedArray a = ctx.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    /** New placeholder drawable: surface background + tinted icon, themed for ctx. */
    public static Drawable placeholder(Context ctx) {
        int bg = themeColor(ctx, R.attr.stationIconBackground, 0xFF2A2A2A);
        int tint = themeColor(ctx, R.attr.stationIconPlaceholderTint, 0xFF8A8A8A);
        Drawable icon = AppCompatResources.getDrawable(ctx, R.drawable.ic_photo_24dp);
        if (icon == null) return new ColorDrawable(bg);
        icon = DrawableCompat.wrap(icon.mutate());
        DrawableCompat.setTint(icon, tint);
        ColorDrawable back = new ColorDrawable(bg);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{back, icon});
        int size = (int) (48 * ctx.getResources().getDisplayMetrics().density);
        int inset = size / 4;
        layers.setLayerInset(1, inset, inset, inset, inset);
        back.setBounds(0, 0, size, size);
        return new SizedLayers(layers, size);
    }

    /** Shows the placeholder (no dimming). */
    public static void showPlaceholder(ImageView view) {
        view.clearColorFilter();
        view.setImageDrawable(placeholder(view.getContext()));
    }

    public static boolean shouldDim(Context ctx) {
        return Utils.isDarkTheme(ctx)
                && PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF_DIM, true);
    }

    /** A real logo was loaded into view: dim it in dark themes if enabled. */
    public static void applyLogoFilter(ImageView view) {
        if (shouldDim(view.getContext())) {
            ColorMatrix m = new ColorMatrix();
            m.setScale(DIM, DIM, DIM, 1f);
            view.setColorFilter(new ColorMatrixColorFilter(m));
        } else {
            view.clearColorFilter();
        }
    }

    /** LayerDrawable with a fixed intrinsic size, so the icon keeps its inset when scaled. */
    private static final class SizedLayers extends LayerDrawable {
        private final int size;

        SizedLayers(LayerDrawable src, int size) {
            super(new Drawable[]{src});
            this.size = size;
        }

        @Override
        public int getIntrinsicWidth() { return size; }

        @Override
        public int getIntrinsicHeight() { return size; }
    }
}
