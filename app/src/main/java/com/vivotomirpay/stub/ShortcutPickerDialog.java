package com.vivotomirpay.stub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Диалог выбора shortcut с ленивой загрузкой содержимого приложений. */
public final class ShortcutPickerDialog {

    public interface OnShortcutPicked {
        void onPicked(ShortcutAction action);
    }

    private static final int ROW_APP = 0;
    private static final int ROW_SHORTCUT = 1;
    private static final int ROW_MESSAGE = 2;

    private static final int SHORTCUTS_UNLOADED = 0;
    private static final int SHORTCUTS_LOADING = 1;
    private static final int SHORTCUTS_LOADED = 2;
    private static final int SHORTCUTS_ERROR = 3;

    private final Activity activity;
    private final Context appContext;
    private final Ui ui;
    private final ShortcutAction current;
    private final OnShortcutPicked picked;
    private final Runnable onBack;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final PickerAdapter adapter = new PickerAdapter();

    private AlertDialog dialog;
    private EditText search;
    private boolean appsLoading;
    private String appsError;
    private int appsRequestToken;
    private int generation;
    private boolean callbackDelivered;
    private volatile boolean disposed;

    /**
     * Показывает picker и возвращает handle для его закрытия из {@code Activity.onDestroy()}.
     * При вызове вне main thread показ планируется, а метод возвращает {@code null}; {@code null}
     * также возвращается, если Activity уже завершается или уничтожена.
     */
    public static ShortcutPickerDialog show(Activity activity, Ui ui, ShortcutAction current,
                                            OnShortcutPicked picked, Runnable onBack) {
        if (activity == null) throw new NullPointerException("activity == null");
        if (ui == null) throw new NullPointerException("ui == null");
        if (picked == null) throw new NullPointerException("picked == null");
        if (!isActivityUsable(activity)) return null;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> show(activity, ui, current, picked, onBack));
            return null;
        }
        ShortcutPickerDialog controller =
                new ShortcutPickerDialog(activity, ui, current, picked, onBack);
        controller.showDialog();
        return controller;
    }

    private ShortcutPickerDialog(Activity activity, Ui ui, ShortcutAction current,
                                 OnShortcutPicked picked, Runnable onBack) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.ui = ui;
        this.current = current;
        this.picked = picked;
        this.onBack = onBack;
    }

    private void showDialog() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(6));

        TextView header = new TextView(activity);
        header.setText(R.string.shortcut_picker_title);
        header.setTextColor(ui.c(Ui.ON_SURFACE));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setTypeface(ui.medium());
        header.setPadding(dp(2), 0, 0, 0);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.bottomMargin = dp(10);
        content.addView(header, headerParams);

        search = new EditText(activity);
        search.setHint(R.string.shortcut_picker_search_hint);
        search.setTextColor(ui.c(Ui.ON_SURFACE));
        search.setHintTextColor(ui.c(Ui.OUTLINE));
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setBackground(ui.roundedStroke(ui.alpha(Ui.SURFACE_HIGH, 0x66), 14,
                ui.c(Ui.OUTLINE), 1));
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView listView = new ListView(activity);
        listView.setAdapter(adapter);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(4));
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, 0);
        listView.setItemsCanFocus(true);
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.max(dp(140), Math.min(dp(360), (int) (screenHeight * 0.45f)));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight);
        listParams.topMargin = dp(8);
        content.addView(listView, listParams);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                for (AppEntry app : apps) app.searchCollapsed = false;
                rebuildRows();
            }
        });

        dialog = new AlertDialog.Builder(activity)
                .setView(content)
                .setNegativeButton(R.string.btn_back, (d, which) -> deliverBack())
                .create();
        dialog.setOnCancelListener(d -> deliverBack());
        dialog.setOnDismissListener(d -> dispose());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(ui.rounded(ui.c(Ui.CARD), 22));
        }
        startAppsLoad();
    }

    private void startAppsLoad() {
        if (!isDialogUsable() || appsLoading) return;
        appsLoading = true;
        appsError = null;
        final int requestGeneration = generation;
        final int requestToken = ++appsRequestToken;
        rebuildRows();
        executor.execute(() -> {
            try {
                final List<AppEntry> loaded = queryInstalledApps();
                postToMain(() -> {
                    if (!isCurrentRequest(requestGeneration)
                            || requestToken != appsRequestToken || !appsLoading) return;
                    appsLoading = false;
                    apps.clear();
                    apps.addAll(loaded);
                    rebuildRows();
                });
            } catch (Exception error) {
                postToMain(() -> {
                    if (!isCurrentRequest(requestGeneration)
                            || requestToken != appsRequestToken || !appsLoading) return;
                    appsLoading = false;
                    appsError = describeError(error);
                    rebuildRows();
                });
            }
        });
    }

    private List<AppEntry> queryInstalledApps() {
        PackageManager pm = appContext.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, 0);
        List<AppEntry> result = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        String ownPackage = appContext.getPackageName();

        for (ResolveInfo info : resolved) {
            if (Thread.currentThread().isInterrupted()) break;
            if (info == null || info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (packageName == null || ownPackage.equals(packageName)
                    || !seenPackages.add(packageName)) continue;

            String label = packageName;
            try {
                CharSequence loadedLabel = info.loadLabel(pm);
                if (loadedLabel != null && loadedLabel.length() > 0) {
                    label = loadedLabel.toString();
                }
            } catch (Exception ignored) {
            }
            Drawable icon = null;
            try {
                icon = info.loadIcon(pm);
            } catch (Exception ignored) {
            }
            result.add(new AppEntry(packageName, label, icon));
        }

        Comparator<AppEntry> byLabel = (left, right) -> {
            int compared = String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label);
            if (compared != 0) return compared;
            compared = left.label.compareTo(right.label);
            if (compared != 0) return compared;
            return left.packageName.compareTo(right.packageName);
        };
        Collections.sort(result, byLabel);
        return result;
    }

    private void startShortcutsLoad(AppEntry app) {
        if (!isDialogUsable()) return;
        if (app.shortcutsState != SHORTCUTS_UNLOADED
                && app.shortcutsState != SHORTCUTS_ERROR) return;
        app.shortcutsState = SHORTCUTS_LOADING;
        app.shortcutsError = null;
        final int requestGeneration = generation;
        final int requestToken = ++app.requestToken;
        final String packageName = app.packageName;
        rebuildRows();
        executor.execute(() -> {
            try {
                final List<ShortcutEntry> loaded = queryShortcuts(packageName);
                postToMain(() -> {
                    if (!isCurrentRequest(requestGeneration)
                            || requestToken != app.requestToken
                            || app.shortcutsState != SHORTCUTS_LOADING) return;
                    app.shortcuts = loaded;
                    app.shortcutsState = SHORTCUTS_LOADED;
                    rebuildRows();
                });
            } catch (Exception error) {
                postToMain(() -> {
                    if (!isCurrentRequest(requestGeneration)
                            || requestToken != app.requestToken
                            || app.shortcutsState != SHORTCUTS_LOADING) return;
                    app.shortcutsError = describeError(error);
                    app.shortcutsState = SHORTCUTS_ERROR;
                    rebuildRows();
                });
            }
        });
    }

    private List<ShortcutEntry> queryShortcuts(String packageName) throws Exception {
        List<ShortcutInfo> infos = ShizukuShortcuts.getShortcuts(appContext, packageName);
        Map<String, ShortcutInfo> unique = new LinkedHashMap<>();
        for (ShortcutInfo info : infos) {
            if (info == null) continue;
            String id = info.getId();
            if (id == null || id.trim().isEmpty() || unique.containsKey(id)) continue;
            unique.put(id, info);
        }

        List<ShortcutEntry> result = new ArrayList<>();
        for (ShortcutInfo info : unique.values()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String id = info.getId();
            String label = labelOf(info);
            int type = info.isPinned()
                    ? R.string.shortcut_type_pinned
                    : info.isDynamic()
                    ? R.string.shortcut_type_dynamic
                    : R.string.shortcut_type_manifest;
            Drawable icon = null;
            try {
                icon = ShizukuShortcuts.getShortcutIcon(appContext, info);
            } catch (Exception ignored) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            }
            result.add(new ShortcutEntry(id, label, icon, info.getRank(), type));
        }

        Collections.sort(result, (left, right) -> {
            int compared = Integer.compare(left.rank, right.rank);
            if (compared != 0) return compared;
            compared = String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label);
            if (compared != 0) return compared;
            compared = left.label.compareTo(right.label);
            if (compared != 0) return compared;
            return left.id.compareTo(right.id);
        });
        return result;
    }

    private static String labelOf(ShortcutInfo info) {
        CharSequence shortLabel = info.getShortLabel();
        if (shortLabel != null && shortLabel.length() > 0) return shortLabel.toString();
        CharSequence longLabel = info.getLongLabel();
        if (longLabel != null && longLabel.length() > 0) return longLabel.toString();
        return info.getId();
    }

    private void onAppClicked(AppEntry app) {
        if (!isDialogUsable()) return;
        String query = currentQuery();
        List<ShortcutEntry> matches = query.isEmpty()
                ? Collections.emptyList() : matchingShortcuts(app, query);
        if (!query.isEmpty() && !matches.isEmpty()) {
            app.searchCollapsed = !app.searchCollapsed;
            rebuildRows();
            return;
        }

        app.expanded = !app.expanded;
        app.searchCollapsed = false;
        if (app.expanded && app.shortcutsState == SHORTCUTS_UNLOADED) {
            startShortcutsLoad(app);
        } else {
            rebuildRows();
        }
    }

    private void retryShortcuts(AppEntry app) {
        if (!isDialogUsable() || app.shortcutsState != SHORTCUTS_ERROR) return;
        app.expanded = true;
        app.searchCollapsed = false;
        startShortcutsLoad(app);
    }

    private void rebuildRows() {
        if (disposed || dialog == null) return;
        List<Row> updated = new ArrayList<>();
        if (appsLoading) {
            updated.add(Row.message(null,
                    activity.getString(R.string.shortcut_picker_loading), true, false, false));
        } else if (appsError != null) {
            updated.add(Row.message(null, loadingError(appsError), false, true, false));
        } else {
            String query = currentQuery();
            int visibleApps = 0;
            for (AppEntry app : apps) {
                boolean appMatches = query.isEmpty()
                        || normalize(app.label).contains(query)
                        || normalize(app.packageName).contains(query);
                List<ShortcutEntry> matches = query.isEmpty()
                        ? Collections.emptyList() : matchingShortcuts(app, query);
                if (!query.isEmpty() && !appMatches && matches.isEmpty()) continue;
                visibleApps++;

                if (query.isEmpty()) {
                    updated.add(Row.app(app, app.expanded));
                    if (app.expanded) appendFullChildren(updated, app);
                } else if (!matches.isEmpty()) {
                    boolean expanded = !app.searchCollapsed;
                    updated.add(Row.app(app, expanded));
                    if (expanded) {
                        for (ShortcutEntry shortcut : matches) {
                            updated.add(Row.shortcut(app, shortcut));
                        }
                    }
                } else if (appMatches && app.expanded) {
                    updated.add(Row.app(app, true));
                    appendFullChildren(updated, app);
                } else {
                    updated.add(Row.app(app, false));
                }
            }
            if (visibleApps == 0) {
                updated.add(Row.message(null,
                        activity.getString(R.string.picker_no_apps), false, false, false));
            }
        }
        rows.clear();
        rows.addAll(updated);
        adapter.notifyDataSetChanged();
    }

    private void appendFullChildren(List<Row> target, AppEntry app) {
        if (app.shortcutsState == SHORTCUTS_LOADING) {
            target.add(Row.message(app,
                    activity.getString(R.string.shortcut_picker_loading), true, false, true));
        } else if (app.shortcutsState == SHORTCUTS_ERROR) {
            target.add(Row.message(app,
                    loadingError(app.shortcutsError), false, true, true));
        } else if (app.shortcutsState == SHORTCUTS_LOADED) {
            if (app.shortcuts.isEmpty()) {
                target.add(Row.message(app,
                        activity.getString(R.string.shortcut_picker_no_shortcuts),
                        false, false, true));
            } else {
                for (ShortcutEntry shortcut : app.shortcuts) {
                    target.add(Row.shortcut(app, shortcut));
                }
            }
        }
    }

    private List<ShortcutEntry> matchingShortcuts(AppEntry app, String query) {
        if (app.shortcutsState != SHORTCUTS_LOADED) return Collections.emptyList();
        List<ShortcutEntry> result = new ArrayList<>();
        for (ShortcutEntry shortcut : app.shortcuts) {
            if (normalize(shortcut.label).contains(query)) result.add(shortcut);
        }
        return result;
    }

    private void pick(AppEntry app, ShortcutEntry shortcut) {
        if (!isDialogUsable() || callbackDelivered) return;
        ShortcutAction action = new ShortcutAction(app.packageName, shortcut.id, shortcut.label);
        callbackDelivered = true;
        dialog.dismiss();
        picked.onPicked(action);
    }

    private void deliverBack() {
        if (callbackDelivered || disposed) return;
        callbackDelivered = true;
        if (dialog != null) dialog.dismiss();
        if (onBack != null) onBack.run();
    }

    /** Идемпотентно закрывает picker без pick/onBack и останавливает фоновые задачи. */
    public void close() {
        if (disposed) return;
        callbackDelivered = true;
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } finally {
            dispose();
        }
    }

    private boolean isCurrentRequest(int requestGeneration) {
        return isDialogUsable() && generation == requestGeneration;
    }

    private boolean isDialogUsable() {
        return !disposed && dialog != null && dialog.isShowing() && isActivityUsable(activity);
    }

    private static boolean isActivityUsable(Activity activity) {
        return !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private void postToMain(Runnable runnable) {
        if (!disposed) mainHandler.post(runnable);
    }

    private void dispose() {
        if (disposed) return;
        disposed = true;
        generation++;
        appsRequestToken++;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(
                Activity.INPUT_METHOD_SERVICE);
        if (imm != null && search != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private String currentQuery() {
        return search == null ? "" : normalize(search.getText().toString().trim());
    }

    private String loadingError(String detail) {
        return activity.getString(R.string.shortcut_picker_loading_error, detail);
    }

    private static String describeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.getDefault());
    }

    private int dp(float value) {
        return (int) ui.dp(value);
    }

    private boolean isCurrent(AppEntry app, ShortcutEntry shortcut) {
        return current != null
                && app.packageName.equals(current.getPackageName())
                && shortcut.id.equals(current.getShortcutId());
    }

    private final class PickerAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return getItem(position).type;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = getItem(position);
            if (convertView == null) convertView = createView(row.type);
            if (row.type == ROW_APP) {
                bindApp((ItemViews) convertView.getTag(), row.app, row.expanded);
            } else if (row.type == ROW_SHORTCUT) {
                bindShortcut((ItemViews) convertView.getTag(), row.app, row.shortcut);
            } else {
                bindMessage((MessageViews) convertView.getTag(), row);
            }
            return convertView;
        }

        private View createView(int type) {
            if (type == ROW_MESSAGE) return createMessageView();

            boolean appRow = type == ROW_APP;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(appRow ? 60 : 54));
            row.setPadding(dp(appRow ? 10 : 32), dp(8), dp(10), dp(8));
            row.setClickable(true);
            row.setFocusable(true);
            row.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setContentDescription(null);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int iconSize = dp(appRow ? 34 : 28);
            row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

            LinearLayout textColumn = new LinearLayout(activity);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(activity);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(title);
            TextView subtitle = new TextView(activity);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(subtitle);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.leftMargin = dp(12);
            row.addView(textColumn, textParams);

            TextView disclosure = new TextView(activity);
            disclosure.setGravity(Gravity.CENTER);
            disclosure.setTextSize(TypedValue.COMPLEX_UNIT_SP, appRow ? 20 : 14);
            disclosure.setContentDescription(null);
            disclosure.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(disclosure, new LinearLayout.LayoutParams(dp(28), dp(36)));

            ItemViews views = new ItemViews(row, icon, title, subtitle, disclosure);
            row.setTag(views);
            return row;
        }

        private View createMessageView() {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout statusLine = new LinearLayout(activity);
            statusLine.setOrientation(LinearLayout.HORIZONTAL);
            statusLine.setGravity(Gravity.CENTER_VERTICAL);
            ProgressBar progress = new ProgressBar(activity);
            progress.setContentDescription(null);
            progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int progressSize = dp(24);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    progressSize, progressSize);
            progressParams.rightMargin = dp(10);
            statusLine.addView(progress, progressParams);
            TextView message = new TextView(activity);
            message.setTextColor(ui.c(Ui.ON_SURFACE_VARIANT));
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            message.setMaxLines(5);
            statusLine.addView(message, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(statusLine, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView retry = new TextView(activity);
            retry.setText(R.string.btn_retry);
            retry.setTextColor(ui.c(Ui.ON_PRIMARY_CONTAINER));
            retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            retry.setTypeface(ui.medium());
            retry.setGravity(Gravity.CENTER);
            retry.setPadding(dp(16), dp(8), dp(16), dp(8));
            retry.setBackground(ui.rippleOnRounded(ui.c(Ui.PRIMARY_CONTAINER), 12));
            retry.setClickable(true);
            retry.setFocusable(true);
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            retryParams.topMargin = dp(8);
            retryParams.gravity = Gravity.END;
            row.addView(retry, retryParams);

            MessageViews views = new MessageViews(row, progress, message, retry);
            row.setTag(views);
            return row;
        }

        private void bindApp(ItemViews views, AppEntry app, boolean expanded) {
            views.row.setBackground(ui.rippleOnRounded(ui.alpha(Ui.SURFACE_HIGH, 0x66), 14));
            bindIcon(views.icon, app.icon, ui.c(Ui.PRIMARY));
            views.title.setText(app.label);
            views.title.setTextColor(ui.c(Ui.ON_SURFACE));
            views.title.setTypeface(ui.medium());
            views.subtitle.setText(app.packageName);
            views.subtitle.setTextColor(ui.c(Ui.ON_SURFACE_VARIANT));
            views.disclosure.setText(expanded ? "▾" : "▸");
            views.disclosure.setTextColor(ui.c(Ui.ON_SURFACE_VARIANT));
            views.row.setContentDescription(app.label + ", " + app.packageName);
            views.row.setOnClickListener(v -> onAppClicked(app));
        }

        private void bindShortcut(ItemViews views, AppEntry app, ShortcutEntry shortcut) {
            boolean selected = isCurrent(app, shortcut);
            views.row.setBackground(selected
                    ? ui.roundedStroke(ui.c(Ui.PRIMARY_CONTAINER), 14, ui.c(Ui.PRIMARY), 1.5f)
                    : ui.rippleOnRounded(ui.alpha(Ui.SURFACE_HIGH, 0x66), 14));
            int foreground = selected ? ui.c(Ui.ON_PRIMARY_CONTAINER) : ui.c(Ui.ON_SURFACE);
            int secondary = selected ? ui.c(Ui.ON_PRIMARY_CONTAINER) : ui.c(Ui.ON_SURFACE_VARIANT);
            Drawable icon = shortcut.icon != null ? shortcut.icon : app.icon;
            bindIcon(views.icon, icon,
                    selected ? ui.c(Ui.ON_PRIMARY_CONTAINER) : ui.c(Ui.PRIMARY));
            views.title.setText(selected
                    ? activity.getString(R.string.picker_current_mark, shortcut.label)
                    : shortcut.label);
            views.title.setTextColor(foreground);
            views.title.setTypeface(selected ? ui.medium() : null);
            views.subtitle.setText(shortcut.typeLabel);
            views.subtitle.setTextColor(secondary);
            views.disclosure.setText(selected ? "✓" : "");
            views.disclosure.setTextColor(foreground);
            views.row.setContentDescription(null);
            views.row.setOnClickListener(v -> pick(app, shortcut));
        }

        private void bindMessage(MessageViews views, Row row) {
            views.row.setPadding(dp(row.indented ? 44 : 12), dp(10),
                    dp(12), dp(10));
            views.row.setBackground(ui.rounded(ui.alpha(Ui.SURFACE_HIGH, 0x44), 14));
            views.progress.setVisibility(row.loading ? View.VISIBLE : View.GONE);
            views.message.setText(row.message);
            views.retry.setVisibility(row.error ? View.VISIBLE : View.GONE);
            views.retry.setClickable(row.error);
            views.retry.setFocusable(row.error);
            if (row.error) {
                views.retry.setOnClickListener(v -> {
                    if (row.app == null) startAppsLoad();
                    else retryShortcuts(row.app);
                });
            } else {
                views.retry.setOnClickListener(null);
            }
        }

        private void bindIcon(ImageView view, Drawable icon, int tint) {
            view.clearColorFilter();
            if (icon != null) {
                view.setImageDrawable(icon);
            } else {
                view.setImageResource(R.drawable.ic_action_app);
                view.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        boolean expanded;
        boolean searchCollapsed;
        int shortcutsState = SHORTCUTS_UNLOADED;
        int requestToken;
        List<ShortcutEntry> shortcuts = Collections.emptyList();
        String shortcutsError;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class ShortcutEntry {
        final String id;
        final String label;
        final Drawable icon;
        final int rank;
        final int typeLabel;

        ShortcutEntry(String id, String label, Drawable icon, int rank, int typeLabel) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.rank = rank;
            this.typeLabel = typeLabel;
        }
    }

    private static final class Row {
        final int type;
        final AppEntry app;
        final ShortcutEntry shortcut;
        final boolean expanded;
        final String message;
        final boolean loading;
        final boolean error;
        final boolean indented;

        private Row(int type, AppEntry app, ShortcutEntry shortcut, boolean expanded,
                    String message, boolean loading, boolean error, boolean indented) {
            this.type = type;
            this.app = app;
            this.shortcut = shortcut;
            this.expanded = expanded;
            this.message = message;
            this.loading = loading;
            this.error = error;
            this.indented = indented;
        }

        static Row app(AppEntry app, boolean expanded) {
            return new Row(ROW_APP, app, null, expanded,
                    null, false, false, false);
        }

        static Row shortcut(AppEntry app, ShortcutEntry shortcut) {
            return new Row(ROW_SHORTCUT, app, shortcut, false,
                    null, false, false, false);
        }

        static Row message(AppEntry app, String message, boolean loading,
                           boolean error, boolean indented) {
            return new Row(ROW_MESSAGE, app, null, false,
                    message, loading, error, indented);
        }
    }

    private static final class ItemViews {
        final LinearLayout row;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final TextView disclosure;

        ItemViews(LinearLayout row, ImageView icon, TextView title,
                  TextView subtitle, TextView disclosure) {
            this.row = row;
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.disclosure = disclosure;
        }
    }

    private static final class MessageViews {
        final LinearLayout row;
        final ProgressBar progress;
        final TextView message;
        final TextView retry;

        MessageViews(LinearLayout row, ProgressBar progress, TextView message, TextView retry) {
            this.row = row;
            this.progress = progress;
            this.message = message;
            this.retry = retry;
        }
    }
}
