package io.github.epv.vectorbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Minimal Google Photos -> Encrypted Photo Vault integration for Vector/LSPosed.
 *
 * Hooks only the public Android Activity lifecycle, not obfuscated
 * Google Photos internals, Photos account tokens, or the backup protocol.
 * No READ_MEDIA permissions and no automatic access to cloud photos.
 */
public final class PhotosHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String GOOGLE_PHOTOS = "com.google.android.apps.photos";
    private static final String EPV_PACKAGE = "io.github.epv.vault";
    private static final String EPV_ACTIVITY = EPV_PACKAGE + ".MainActivity";
    private static final String PANEL_TAG = "epv_vector_bridge_panel_v1";
    private static volatile String moduleApkPath;

    @Override
    public void initZygote(StartupParam startupParam) {
        moduleApkPath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!GOOGLE_PHOTOS.equals(param.packageName)) return;
        if (!GOOGLE_PHOTOS.equals(param.processName)) return;

        // Uses only the visible PhotoCellView rendering; never queries
        // Google's cloud API or credentials. If the cell class changes,
        // the rest of the bridge remains usable.
        NsfwGridBadge.install(param.classLoader, moduleApkPath);
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hookParam) {
                    if (!(hookParam.thisObject instanceof Activity)) return;
                    try {
                        attachPanel((Activity) hookParam.thisObject);
                    } catch (Throwable ignored) {
                        // Never crash the host photo gallery because of our overlay.
                    }
                }
            });
        } catch (Throwable ignored) {
            // Avoid a crash if an unsupported Vector build rejects the hook.
        }
    }

    private static void attachPanel(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) return;
        FrameLayout frame = (FrameLayout) decor;
        if (frame.findViewWithTag(PANEL_TAG) != null) return;

        NsfwGridBadge.restoreSetting(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setTag(PANEL_TAG);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setElevation(dp(activity, 8));

        TextView gallery = button(activity, "EPV");
        gallery.setContentDescription("Открыть Encrypted Photo Vault");
        gallery.setOnClickListener(v -> openVault(activity, "cloud"));
        panel.addView(gallery);

        TextView scan = button(activity, "18+");
        scan.setContentDescription("Открыть локальный поиск 18+ в Encrypted Photo Vault");
        scan.setOnClickListener(v -> openVault(activity, "nsfw"));
        scan.setOnLongClickListener(v -> {
            openVault(activity, "organize");
            return true;
        });
        scan.setContentDescription("Поиск 18+: нажать для шифрования, удержать для сортировки в папку без шифрования");
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        second.leftMargin = dp(activity, 6);
        panel.addView(scan, second);

        TextView toggleBadges = button(activity,
                NsfwGridBadge.isEnabled(activity) ? "Метки ✓" : "Метки —");
        toggleBadges.setContentDescription("Включить или выключить локальные метки 18+");
        toggleBadges.setOnClickListener(v -> {
            boolean active = NsfwGridBadge.toggle(activity);
            toggleBadges.setText(active ? "Метки ✓" : "Метки —");
            Toast.makeText(activity, active ? "18+ метки включены" : "18+ метки выключены",
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.leftMargin = dp(activity, 6);
        panel.addView(toggleBadges, toggleParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        params.bottomMargin = dp(activity, 98);
        params.rightMargin = dp(activity, 14);
        frame.addView(panel, params);
    }

    private static TextView button(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(activity, 16), dp(activity, 9),
                dp(activity, 16), dp(activity, 9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEB263650);
        background.setCornerRadius(dp(activity, 18));
        view.setBackground(background);
        view.setClickable(true);
        return view;
    }

    private static void openVault(Activity activity, String mode) {
        Intent open = new Intent(Intent.ACTION_VIEW);
        open.setClassName(EPV_PACKAGE, EPV_ACTIVITY);
        open.putExtra("epv_launch", mode);
        try {
            activity.startActivity(open);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(activity,
                    "Сначала установите Encrypted Photo Vault",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
