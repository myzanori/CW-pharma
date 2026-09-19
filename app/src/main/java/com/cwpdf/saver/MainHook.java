package com.cwpdf.saver;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.cwpdf.saver.util.DownloadEngine;
/**
 * Durable hook set for CW Pharmacy.
 *
 * <p>The 1.1.3 -> 1.1.4 update broke the previous build because it hooked
 * <b>leaf</b> members: {@code PdfViewerActivity.onCreate} and a hard-coded list
 * of {@code isScreenshotEnabled} owners. The app moved {@code onCreate} into
 * {@code BasePdfFileRendererActivity}, added {@code AndroidXPdfViewerActivity}
 * and added nine new gate classes, so every one of those hooks silently
 * no-op'd.</p>
 *
 * <p>This version only hooks seams that are stable across app updates:</p>
 * <ul>
 *   <li>{@code android.view.Window} - strip {@code FLAG_SECURE} so screenshots
 *       and screen recordings work.</li>
 *   <li>Class loading - discover every {@code isScreenshotEnabled} owner as it
 *       loads instead of hard-coding a list, so newly added gate classes are
 *       covered automatically.</li>
 *   <li>{@code android.app.Activity#onResume} - inject the PDF bar based on the
 *       <i>intent extras</i> a viewer receives, not on the viewer's class name.
 *       Any future viewer implementation is covered.</li>
 * </ul>
 *
 * <p>Background on the app's own design, which this module cooperates with:</p>
 * <pre>
 *   ec.b.g / ec.b.h  = "secure windows" flag, true by default
 *   every activity  : if (ec.b.g) window.setFlags(FLAG_SECURE, FLAG_SECURE)
 *   isScreenshotEnabled() == !ACTIVATE_SCREENSHOT &amp;&amp; (windowFlags &amp; FLAG_SECURE) == 0
 * </pre>
 * <p>{@code isScreenshotEnabled()} therefore flips to {@code true} the moment we
 * strip {@code FLAG_SECURE}, which is what used to block paid content. Forcing
 * it to {@code false} keeps content open while real screenshots stay allowed.</p>
 */
public class MainHook extends XposedModule {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String TAG = "CWPDFSaver";

    /** Package the module is scoped to. */
    private static final String TARGET_PACKAGE = "com.gvxhgw.qwporr";
    /** Vendor SDK package that all app code lives under. */
    private static final String APP_CLASS_PREFIX = "com.appx.core.";
    /** {@code android.view.WindowManager.LayoutParams.FLAG_SECURE}. */
    private static final int FLAG_SECURE = 0x2000;

    private static boolean hasShownPopup = false;

    /** Gate classes we have already hooked, so the scanner never double-hooks. */
    private static final Set<String> HOOKED_GATE_CLASSES = ConcurrentHashMap.newKeySet();

    /**
     * Activities already pushed into the module database. Identity based and
     * weak, so a re-created activity is synced again but repeat resumes are not.
     */
    private final Set<Activity> syncedActivities =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));

    /**
     * Gate classes known to exist in 1.1.4. The runtime scanner below discovers
     * everything, but seeding the known set means the crash fix and the content
     * gates are active even if a class was already loaded before the scanner was
     * installed. Unknown names are ignored, so this list can go stale safely.
     */
    private static final String[] KNOWN_GATE_CLASSES = {
            // activities
            "com.appx.core.activity.MainActivity",
            "com.appx.core.activity.SplashActivity",
            "com.appx.core.activity.PreviousLiveActivity",
            "com.appx.core.activity.FolderCoursesContentsActivity",
            "com.appx.core.activity.PaidCourseRecordActivity",
            "com.appx.core.activity.FolderChapterLayer",
            "com.appx.core.activity.RecentLearningActivity",
            // fragments
            "com.appx.core.fragment.BasicHomeFragment",
            "com.appx.core.fragment.BonusContentsFragment",
            "com.appx.core.fragment.ContentsLayerFragment",
            "com.appx.core.fragment.DemoFragment",
            "com.appx.core.fragment.FolderCourseContentsFragment",
            "com.appx.core.fragment.LiveUpcomingCourseFragment",
            "com.appx.core.fragment.MainHomeFragment",
            "com.appx.core.fragment.NarayanaFolderNewCourseDetailFragment",
            "com.appx.core.fragment.OTTFragment",
            "com.appx.core.fragment.PreviousLiveVideosFragment",
            "com.appx.core.fragment.RecentClassesFragment",
            "com.appx.core.fragment.RecordedUpcomingFragment",
            "com.appx.core.fragment.ThirdHomeFragment",
            "com.appx.core.fragment.TimeTableVideoFragment",
            "com.appx.core.fragment.TimeTableVideoFragmentTypeNarayana",
            "com.appx.core.fragment.YesOfficerHomeFragment",
    };

    public MainHook() {
        super();
        Log.d(TAG, "MainHook instantiated (libxposed API 102)");
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        Log.d(TAG, "onHotReloading: module is preparing to hot reload");
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        Log.d(TAG, "onHotReloaded: module hot reload complete");
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        super.onPackageLoaded(param);
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;

        Log.d(TAG, "CW Pharma package loaded - installing durable hooks");

        try {
            installScreenCaptureBypass();
            installPlayerGateBypass(param.getDefaultClassLoader());
            installDeveloperOptionsBypass(param.getDefaultClassLoader());
            installActivityHook();
            seedKnownGateClasses(param.getDefaultClassLoader());
        } catch (Throwable t) {
            Log.e(TAG, "onPackageLoaded Error", t);
        }
    }

    // ------------------------------------------------------------------
    // Screenshot / content-gate bypass
    // ------------------------------------------------------------------

    private void installScreenCaptureBypass() {
        stripFlagSecure();
        installGateScanner();
    }

    /**
     * Removes {@code FLAG_SECURE} before it ever reaches the window. Every one of
     * the ~170 sites in the app applies it through {@code setFlags} /
     * {@code addFlags} (verified against 1.1.4), and {@code addFlags} delegates
     * to {@code setFlags}, so these two hooks are the complete choke point.
     */
    private void stripFlagSecure() {
        try {
            Method setFlags = Window.class.getDeclaredMethod("setFlags", int.class, int.class);
            hook(setFlags).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                int flags = (Integer) chain.getArg(0);
                int mask = (Integer) chain.getArg(1);
                return chain.proceed(new Object[]{flags & ~FLAG_SECURE, mask});
            });
            Log.d(TAG, "FLAG_SECURE strip installed on Window.setFlags");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Window.setFlags", t);
        }

        try {
            Method addFlags = Window.class.getDeclaredMethod("addFlags", int.class);
            hook(addFlags).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                int flags = (Integer) chain.getArg(0);
                return chain.proceed(new Object[]{flags & ~FLAG_SECURE});
            });
            Log.d(TAG, "FLAG_SECURE strip installed on Window.addFlags");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Window.addFlags", t);
        }
    }

    /**
     * Installs the class-load scanner that auto-hooks {@code isScreenshotEnabled}
     * in every class the app loads, replacing the old hard-coded list.
     */
    private void installGateScanner() {
        XposedInterface.Hooker scanHooker = chain -> {
            Object result = chain.proceed();
            if (result instanceof Class) {
                try {
                    hookGateMethods((Class<?>) result);
                } catch (Throwable t) {
                    Log.w(TAG, "Gate scan failed for " + result, t);
                }
            }
            return result;
        };

        // Preferred seam. findClass() is only invoked for classes that actually
        // live in the app's own dex files, so framework classes cost nothing, and
        // it is a virtual call that the runtime cannot inline away.
        try {
            Method findClass = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredMethod("findClass", String.class);
            hook(findClass).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(scanHooker);
            Log.d(TAG, "Gate scanner installed on BaseDexClassLoader.findClass");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "findClass scanner unavailable, falling back to loadClass", t);
        }

        // Fallback seam.
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            hook(loadClass).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(scanHooker);
            // loadClass(String) is a one-liner and is routinely inlined into its
            // callers, which would hide the hook. Deoptimising it forces those
            // callers back through the virtual call.
            try {
                deoptimize(ClassLoader.class.getDeclaredMethod("loadClass", String.class));
            } catch (Throwable ignored) {
            }
            Log.d(TAG, "Gate scanner installed on ClassLoader.loadClass (fallback)");
        } catch (Throwable t) {
            Log.e(TAG, "Gate scanner could not be installed", t);
        }
    }

    /**
     * Bypasses the player-side screenshot gate.
     *
     * <p>{@code mm.b.F(Activity)} is the only place in 1.1.4 - outside the
     * {@code isScreenshotEnabled} family - that reads
     * {@code window.attributes.flags & FLAG_SECURE}. Every player activity
     * (VideoDownloadActivity, ExoLiveActivity, StreamingActivity, the WebView
     * and Youtube players, ...) does:</p>
     *
     * <pre>
     *   if (mm.b.F(this)) finish();
     * </pre>
     *
     * <p>so a {@code true} return shows "Please disable screenshot" and closes
     * the player. Because we strip FLAG_SECURE this gate would always fire, so
     * it has to be forced to {@code false} as well.</p>
     *
     * <p>The name is obfuscated and may move in a future build. If that happens
     * the log reports that it was not found, and only this one entry needs
     * re-pointing.</p>
     */
    private void installPlayerGateBypass(ClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            Class<?> clazz = classLoader.loadClass("mm.b");
            Method gate = clazz.getDeclaredMethod("F", Activity.class);
            hook(gate).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> Boolean.FALSE);
            Log.d(TAG, "Bypassed player screenshot gate mm.b.F(Activity)");
        } catch (Throwable t) {
            Log.w(TAG, "Player screenshot gate mm.b.F(Activity) not found", t);
        }
    }

    /**
     * Bypasses the "Please Disable USB Debugging" gate.
     *
     * <p>When the server flag {@code FirebaseVersionModel.getUsb()} is 1,
     * {@code DashboardViewModel} refuses to load and calls
     * {@code checkResult(usb_debugging, usb_debugging_message, ...)} whenever</p>
     *
     * <pre>
     *   Settings.Secure.getInt(cr, "development_settings_enabled", 0) != 0
     * </pre>
     *
     * <p>i.e. whenever Developer Options is on - which is exactly what happens
     * the moment USB debugging is enabled to connect a PC. Note the key lives
     * in {@code Settings.Global} but {@code Settings.Secure} transparently
     * redirects it (it is in AOSP's MOVED_TO_GLOBAL set), so hooking the
     * {@code Secure} accessor is enough and covers both call sites in the app.</p>
     */
    private void installDeveloperOptionsBypass(ClassLoader classLoader) {
        XposedInterface.Hooker devOptionsHook = chain -> {
            Object name = chain.getArg(1);
            if (!"development_settings_enabled".equals(name)) {
                return chain.proceed();
            }
            Object actual = chain.proceed();
            Log.d(TAG, "development_settings_enabled suppressed (real value=" + actual + ")");
            return 0;
        };

        // Both accessors are used by the app for the same key.
        hookSettingsGetInt(Settings.Secure.class, devOptionsHook, "Settings.Secure");
        hookSettingsGetInt(Settings.Global.class, devOptionsHook, "Settings.Global");

        if (classLoader == null) return;

        // The SplashActivity path: DashboardViewModel.isDevelopmentSettingsEnabled().
        try {
            Class<?> dashboard = classLoader.loadClass("com.appx.core.viewmodel.DashboardViewModel");
            Method gate = dashboard.getDeclaredMethod("isDevelopmentSettingsEnabled", Context.class);
            hook(gate).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Log.d(TAG, "DashboardViewModel.isDevelopmentSettingsEnabled() -> false");
                return Boolean.FALSE;
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook isDevelopmentSettingsEnabled", t);
        }

        // Upstream of both Settings reads: when the server flag `usb` is 1 the app
        // runs the developer-options check at all. Reporting 0 skips the whole
        // branch, which also survives the Settings hooks being inlined away.
        try {
            Class<?> model = classLoader.loadClass("com.appx.core.model.FirebaseVersionModel");
            Method getUsb = model.getDeclaredMethod("getUsb");
            hook(getUsb).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Log.d(TAG, "FirebaseVersionModel.getUsb() -> 0 (USB gate disabled)");
                return 0L;
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook FirebaseVersionModel.getUsb", t);
        }
    }

    private void hookSettingsGetInt(Class<?> settingsClass, XposedInterface.Hooker hooker, String label) {
        try {
            Method getInt = settingsClass.getDeclaredMethod(
                    "getInt", ContentResolver.class, String.class, int.class);
            hook(getInt).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker);
            Log.d(TAG, "Bypassed developer-options gate on " + label + ".getInt");
        } catch (Throwable t) {
            Log.w(TAG, "Could not hook " + label + ".getInt", t);
        }
    }

    /**
     * Hooks every no-arg {@code isScreenshotEnabled} declared by {@code clazz}.
     *
     * <p>The boolean variant is the content gate and is forced to {@code false}.
     * The void variant dereferences a {@code crashViewModel} that is still null
     * while the activity is starting, which is the launch crash, so it is
     * no-op'd.</p>
     */
    private void hookGateMethods(Class<?> clazz) {
        String className = clazz.getName();
        if (!className.startsWith(APP_CLASS_PREFIX)) return;
        if (!HOOKED_GATE_CLASSES.add(className)) return;

        int hooked = 0;
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"isScreenshotEnabled".equals(method.getName())) continue;
                if (method.getParameterCount() != 0) continue;

                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> Boolean.FALSE);
                    hooked++;
                } else if (returnType == void.class) {
                    hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> null);
                    hooked++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not inspect " + className, t);
        }

        if (hooked > 0) {
            Log.d(TAG, "Hooked " + hooked + " gate method(s) in " + className);
        }
    }

    /** Eagerly hooks the gate classes known to exist, ignoring anything renamed. */
    private void seedKnownGateClasses(ClassLoader classLoader) {
        if (classLoader == null) return;
        int seeded = 0;
        for (String className : KNOWN_GATE_CLASSES) {
            try {
                hookGateMethods(classLoader.loadClass(className));
                seeded++;
            } catch (Throwable ignored) {
                // Not present in this build; the scanner will pick up whatever
                // replaced it.
            }
        }
        Log.d(TAG, "Seeded " + seeded + "/" + KNOWN_GATE_CLASSES.length + " known gate classes");
    }

    // ------------------------------------------------------------------
    // Activity hook: PDF download bar + welcome popup
    // ------------------------------------------------------------------

    private void installActivityHook() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity activity = (Activity) self;
                    try {
                        handlePdfViewer(activity);
                    } catch (Throwable t) {
                        Log.e(TAG, "PDF handling failed", t);
                    }
                    try {
                        maybeShowWelcomePopup(activity);
                    } catch (Throwable t) {
                        Log.e(TAG, "Welcome popup failed", t);
                    }
                }
                return result;
            });
            Log.d(TAG, "Activity.onResume hook installed (PDF bar + welcome popup)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Activity.onResume", t);
        }
    }

    /**
     * Injects the download bar into whatever activity is showing a PDF.
     *
     * <p>Viewers are recognised by the extras they receive ({@code url} /
     * {@code uri}) plus a {@code pdf} class name, so this survives the viewer
     * being renamed, moved to a base class or replaced entirely. In 1.1.4 the
     * viewers are {@code PdfViewerActivity}, {@code AndroidXPdfViewerActivity},
     * {@code PdfViewer2Activity}, {@code PdfWebViewActivity},
     * {@code NewPDFViewerActivity} and {@code BasePdfFileRendererActivity}.</p>
     */
    private void handlePdfViewer(Activity activity) {
        if (!activity.getClass().getName().toLowerCase(Locale.ROOT).contains("pdf")) return;

        Intent intent = activity.getIntent();
        if (intent == null) return;

        String pdfUrl = intent.getStringExtra("url");
        Uri localUri = intent.getParcelableExtra("uri");
        if ((pdfUrl == null || pdfUrl.isEmpty()) && localUri == null) return;

        String key = intent.getStringExtra("key");
        String title = intent.getStringExtra("title");
        boolean isEncrypted = intent.getBooleanExtra("encrypted", false);

        Log.d(TAG, "PDF viewer " + activity.getClass().getSimpleName()
                + " - url=" + pdfUrl + " uri=" + localUri + " encrypted=" + isEncrypted);

        if (syncedActivities.add(activity)) {
            syncPdfToModule(activity, pdfUrl, localUri, key, title, isEncrypted);
        }
        injectDownloadButton(activity, pdfUrl, localUri, key, title, isEncrypted);
    }

    private void maybeShowWelcomePopup(Activity activity) {
        if (hasShownPopup) return;

        // Skip splash screens so the popup doesn't get destroyed when the logo disappears
        String actName = activity.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (actName.contains("splash") || actName.contains("launch") || actName.contains("start")) {
            return;
        }

        hasShownPopup = true;
        showWelcomePopup(activity);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void syncPdfToModule(Activity activity, String pdfUrl, Uri localUri, String key, String title, boolean isEncrypted) {
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("title", title != null ? title : "Unknown PDF");
            values.put("url", pdfUrl != null ? pdfUrl : "");
            values.put("uri", localUri != null ? localUri.toString() : "");
            values.put("decryption_key", key != null ? key : "");
            values.put("is_encrypted", isEncrypted ? 1 : 0);
            values.put("timestamp", System.currentTimeMillis());

            Uri providerUri = Uri.parse("content://com.cwpdf.saver.provider/pdfs");
            activity.getContentResolver().insert(providerUri, values);

            Log.d(TAG, "Successfully synced PDF to module UI: " + title);

            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(activity, "PDF synced to CW PDF Saver app!", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync PDF to module", e);
        }
    }

    private void injectDownloadButton(final Activity activity, final String pdfUrl, final Uri localUri, final String key, final String title, final boolean isEncrypted) {
        try {
            View root = activity.findViewById(android.R.id.content);
            if (root == null || root.findViewWithTag("cw_download_bar") != null) return;

            float dp = dpToPx(activity, 1);
            int barHeight = (int)(48 * dp);

            // Bottom bar that spans from the START (left edge) up to the existing
            // fab_menu button on the right, without overlapping it.
            android.widget.LinearLayout bar = new android.widget.LinearLayout(activity);
            bar.setTag("cw_download_bar");
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER);
            bar.setClickable(true);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF0061A4);
            bg.setCornerRadius((int)(24 * dp));
            bar.setBackground(bg);
            bar.setElevation(6 * dp);

            android.widget.TextView label = new android.widget.TextView(activity);
            label.setText("⬇  Download PDF");
            label.setTextSize(16);
            label.setTextColor(0xFFFFFFFF);
            label.setGravity(Gravity.CENTER);
            bar.addView(label);

            // Parent of android.R.id.content is a FrameLayout -> FrameLayout.LayoutParams
            // honors gravity, which fixes the top-left placement.
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight
            );
            barParams.gravity = Gravity.BOTTOM | Gravity.START;
            barParams.setMargins((int)(14 * dp), 0, (int)(78 * dp), (int)(14 * dp));
            bar.setLayoutParams(barParams);

            bar.setOnClickListener(v -> {
                Toast.makeText(activity, "Downloading PDF...", Toast.LENGTH_SHORT).show();
                DownloadEngine.startDownload(activity, pdfUrl, localUri, key, title, isEncrypted, new DownloadEngine.DownloadCallback() {
                    @Override
                    public void onSuccess(String fileName) {
                        new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(activity, "Saved: " + fileName, Toast.LENGTH_LONG).show());
                    }
                    @Override
                    public void onError(String errorMsg) {
                        new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(activity, "Download failed: " + errorMsg, Toast.LENGTH_LONG).show());
                    }
                });
            });

            // Measure the existing fab_menu so the bar ends exactly before it.
            try {
                int fabMenuId = activity.getResources().getIdentifier("fab_menu", "id", activity.getPackageName());
                View fabMenu = fabMenuId != 0 ? activity.findViewById(fabMenuId) : null;
                if (fabMenu != null) {
                    bar.post(() -> {
                        int rightMargin = root.getWidth() - fabMenu.getLeft() + (int)(10 * dp);
                        barParams.setMargins((int)(14 * dp), 0, rightMargin, (int)(14 * dp));
                        bar.setLayoutParams(barParams);
                    });
                }
            } catch (Throwable ignored) {}

            // Hide the bar while the PDF is being scrolled, show it again after.
            // Attach to the CONTENT ROOT (parent), NOT the PDFView: PDFView manages
            // its own touch internally via setOnTouchListener, so touching it there
            // would break scrolling/pinch. On the parent we just observe without
            // consuming (return false), so scroll still reaches the PDFView.
            try {
                root.setOnTouchListener((v, event) -> {
                    int action = event.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_MOVE) {
                        bar.setVisibility(View.GONE);
                    } else if (action == android.view.MotionEvent.ACTION_UP
                            || action == android.view.MotionEvent.ACTION_CANCEL) {
                        bar.postDelayed(() -> bar.setVisibility(View.VISIBLE), 300);
                    }
                    return false;
                });
            } catch (Throwable ignored) {}

            if (root instanceof ViewGroup) {
                ((ViewGroup) root).addView(bar, barParams);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error injecting download button", t);
        }
    }

    private int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    private void showWelcomePopup(Activity activity) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                // Create a custom MD3 style dialog from scratch to avoid host app theme dependencies
                android.app.Dialog dialog = new android.app.Dialog(activity);
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                }

                android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
                container.setOrientation(android.widget.LinearLayout.VERTICAL);
                container.setPadding(dpToPx(activity, 24), dpToPx(activity, 24), dpToPx(activity, 24), dpToPx(activity, 24));

                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                int nightModeFlags = activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                boolean isDarkMode = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                bg.setColor(isDarkMode ? 0xFF2D2F31 : 0xFFF3F4F9); // MD3 surface color
                bg.setCornerRadius(dpToPx(activity, 28)); // MD3 Dialog corner radius
                container.setBackground(bg);

                // Title
                android.widget.TextView title = new android.widget.TextView(activity);
                title.setText("CW Pharmacy PDF Saver");
                title.setTextSize(24);
                title.setTextColor(isDarkMode ? 0xFFE3E2E6 : 0xFF1A1C1E);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                container.addView(title);

                // Message
                android.widget.TextView message = new android.widget.TextView(activity);
                message.setText("Module is active! 🚀\n\nMade by myzanori.\nKnowledge must be free, accessible, and shareable for all.");
                message.setTextSize(14);
                message.setTextColor(isDarkMode ? 0xFFC4C6D0 : 0xFF44474E);
                message.setPadding(0, dpToPx(activity, 16), 0, dpToPx(activity, 24));
                container.addView(message);

                // Buttons container
                android.widget.LinearLayout buttonContainer = new android.widget.LinearLayout(activity);
                buttonContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
                buttonContainer.setGravity(Gravity.CENTER_HORIZONTAL);

                android.widget.LinearLayout.LayoutParams btnParams = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 48)
                );
                btnParams.bottomMargin = dpToPx(activity, 8);

                // Telegram Button (Primary Filled)
                android.widget.Button btnTelegram = new android.widget.Button(activity);
                btnTelegram.setText("Join Telegram");
                btnTelegram.setTextColor(isDarkMode ? 0xFF00325B : 0xFFFFFFFF);
                btnTelegram.setAllCaps(false);
                android.graphics.drawable.GradientDrawable btnTelBg = new android.graphics.drawable.GradientDrawable();
                btnTelBg.setColor(isDarkMode ? 0xFFD1E4FF : 0xFF0061A4);
                btnTelBg.setCornerRadius(dpToPx(activity, 24)); // Pill shape
                btnTelegram.setBackground(btnTelBg);
                btnTelegram.setOnClickListener(v -> {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+OQA0X-ECCHI4ZmU1")));
                });
                buttonContainer.addView(btnTelegram, btnParams);

                // GitHub Button (Secondary Tonal)
                android.widget.Button btnGithub = new android.widget.Button(activity);
                btnGithub.setText("Star on GitHub");
                btnGithub.setTextColor(isDarkMode ? 0xFFE3E2E6 : 0xFF1A1C1E);
                btnGithub.setAllCaps(false);
                android.graphics.drawable.GradientDrawable btnGitBg = new android.graphics.drawable.GradientDrawable();
                btnGitBg.setColor(isDarkMode ? 0xFF44474E : 0xFFE0E2EC);
                btnGitBg.setCornerRadius(dpToPx(activity, 24));
                btnGithub.setBackground(btnGitBg);
                btnGithub.setOnClickListener(v -> {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/myzanori/CW-pharma")));
                });
                buttonContainer.addView(btnGithub, btnParams);

                // Close Button (Text style)
                android.widget.Button btnClose = new android.widget.Button(activity);
                btnClose.setText("Close");
                btnClose.setTextColor(isDarkMode ? 0xFFAEC6FF : 0xFF0061A4);
                btnClose.setAllCaps(false);
                btnClose.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                btnClose.setOnClickListener(v -> dialog.dismiss());
                buttonContainer.addView(btnClose, btnParams);

                container.addView(buttonContainer);

                dialog.setContentView(container);
                dialog.setCancelable(true);

                int width = (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.85);
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);

                dialog.show();

            } catch (Exception e) {
                Log.e(TAG, "Failed to show popup", e);
            }
        }, 500); // 500ms delay to ensure activity window is fully attached
    }
}
