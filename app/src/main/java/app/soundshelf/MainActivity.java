package app.soundshelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private int BG, PAPER, INK, MUTED, ACCENT, ACCENT_TINT, SOFT, ON_ACCENT, RIPPLE;
    private boolean darkAppearance;
    private static final int SAND = 0xfff1e2cc;
    private static final int AMBER = 0xff86530e;
    private static final int ONYX = 0xff173c33;
    private static final int CARD_RADIUS = 16, PILL_RADIUS = 24, SHEET_RADIUS = 30;
    private static final int CALM_TEAL = 0xff245b5b, PLUM = 0xff62466d, GOLD = 0xff77501d;
    private static final int OCEAN = 0xff2d4e76, BERRY = 0xff843c42, SLATE = 0xff405665;
    private static final int IMPORT_MUSIC = 1, IMPORT_BOOKS = 2;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = Executors.newFixedThreadPool(5);
    private final java.util.concurrent.ExecutorService images = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> covers = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final LruCache<String, Long> recentCoverFailures = new LruCache<>(256);
    private final LinkedHashMap<String, List<CoverTarget>> pendingCovers = new LinkedHashMap<>();
    private final ArrayDeque<Runnable> back = new ArrayDeque<>();
    private MediaSources sources;
    private SharedPreferences prefs;
    private MediaCache mediaCache;
    private LibraryDiscovery libraryDiscovery;
    private PlaylistRefresh playlists;
    private SourceUpdates updates;
    private final Runnable updateObserver = this::showUpdateCount;
    private final Map<String, FrostedCard> onlineEntryCards = new LinkedHashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener entryCoverObserver = (settings, key) -> {
        FrostedCard card = onlineEntryCards.get(key);
        if (card != null) loadMusicEntryCover(card, playlists.discoveryCovers(key));
        bookSourcesChanged(key);
    };
    private int chartEntryRequest;
    private boolean chartEntryLoading;
    private Runnable screen;
    private LinearLayout root;
    private LinearLayout body;
    private ScrollView pageScroll;
    private final java.util.Set<FrostedCard> glassCards = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Rect visibleCard = new Rect();
    private final ArrayDeque<ChartCoverCard> pendingChartCovers = new ArrayDeque<>();
    private final LruCache<String, Long> lastChartCoverFetch = new LruCache<>(128);
    private final LruCache<String, Bitmap> chartArtwork = new LruCache<>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final LruCache<String, ChartPageState> chartPages = new LruCache<>(6);
    private ChartPageState activeCharts;
    private boolean refreshChartArtwork;
    private int chartCoverRequests;
    private final android.util.SparseArray<Bitmap> homeArt = new android.util.SparseArray<>();
    private boolean homeVisible;
    private HomeTemplate displayedHomeTemplate;
    private LinearLayout musicResumeContainer;
    private PlaybackService musicResumePlayer;
    private int musicResumeRevision, musicResumeDuration;
    private ProgressBar musicResumeProgress;
    private TextView musicResumeCaption;
    private final BroadcastReceiver appearanceClock = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshAppearance(false);
            refreshHomeTemplate();
        }
    };
    private CoverViewport coverViewport;
    private final BookDiscovery.Cache bookDiscoveries = new BookDiscovery.Cache(64, 4000);
    private BookDiscovery activeBookDiscovery;
    private MediaSources.BookRequest bookDiscoveryRequest;
    private LinearLayout bookDiscoveryFilters, bookDiscoveryResults, bookDiscoveryFooter;
    private boolean bookDiscoveryLoading, bundledBooksLoading;
    private String bookDiscoverySource = "";
    private LinearLayout mini;
    private WebView browser;
    private TextView miniTitle, miniSubtitle, playerStatus, sourceStatus, updateLabel;
    private FrameLayout homeSettings;
    private FrameLayout miniCover, miniToggle;
    private ImageView miniArtwork;
    private Bitmap miniBitmap;
    private UiIcon miniToggleIcon;
    private boolean miniWantsPause;
    private final Runnable hideMiniToggle = () -> {
        if (miniToggle != null) miniToggle.animate().alpha(0f).setDuration(160).start();
    };
    private ProgressBar miniProgress;
    private PlayerStage playerStage;
    private TextView elapsed, totalTime;
    private FrameLayout playToggle, lyricsControl, playerFavorite, lyricFavorite, playerMode, sleepControl, airPlayControl;
    private AlertDialog airPlayPicker;
    private SeekBar playerVolume;
    private boolean draggingVolume;
    private AlbumBackdrop backdrop;
    private LinearLayout lyricTrack, playerMetadata, playerFooter;
    private View playerHandle;
    private RecordArt recordArt;
    private View playerArtwork;
    private android.app.Dialog playerMenu;
    private android.app.Dialog effectsDialog;
    private ScrollView lyricsScroll;
    private LinearLayout lyricRows;
    private android.graphics.RenderEffect lyricBlur;
    private List<Lyrics.Line> playerLyrics = List.of();
    private int lyricHighlight = -1;
    private long lyricManualUntil;
    private enum PlayerPanel { ARTWORK, LYRICS, FULLSCREEN_LYRICS }
    private PlayerPanel playerPanel = PlayerPanel.ARTWORK;
    private boolean playerPage;
    private int miniRevision = -1, miniPageId = -1;
    private PlaybackService miniService, displayedPlayer;
    private android.app.Dialog queueDialog;
    private LinearLayout queueRows;
    private TextView queueHeading;
    private int sheetVersion = -1, sheetIndex = -1;
    private LinearLayout sourceRows;
    private SeekBar seek;
    private int shownRevision, lastSourceVersion = -1;
    private PlaybackService lastSourcePlayer;
    private long sourceVisibleUntil;
    private boolean bookArtwork, playlistScreen;
    private boolean tracking;
    private boolean clearingServerHistory;
    private int generation;
    private String online = "all", listPlatform = "all", chartPlatform = "wy";
    private String listQuery = "", chartQuery = "", musicQuery = "", libraryQuery = "";
    private boolean grid = true;
    private String tag = "", tagName = "全部分类", sort = "@hot", sortName = "最热";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            PlaybackService player = PlaybackService.active;
            if (musicResumeContainer != null && (musicResumePlayer != player
                || musicResumeRevision != (player == null ? -1 : player.revision())
                || musicResumeDuration != (player == null || player.book ? 0 : player.duration()))) renderMusicResume();
            if (musicResumeContainer != null && player != null && !player.book && musicResumeCaption != null) {
                var recent = player.musicContinuation();
                if (recent != null && recent.available(sources.navCacheKey())) {
                    musicResumeCaption.setText(musicResumeDetail(recent));
                    if (musicResumeProgress != null) musicResumeProgress.setProgress(progressOf(recent.position(), recent.duration()));
                }
            }
            mini.setVisibility(player == null || playerPage ? View.GONE : View.VISIBLE);
            if (player != null) {
                String lyricMode = prefs.getString("mini-lyrics", "off");
                var liveLyrics = player.miniLyrics("double".equals(lyricMode) ? 2 : 1);
                boolean showLyric = !"off".equals(lyricMode) && !liveLyrics.isEmpty() && player.error.isEmpty();
                miniTitle.setText(showLyric ? liveLyrics.get(0) : player.title);
                miniSubtitle.setText(showLyric && liveLyrics.size() > 1 ? liveLyrics.get(1)
                    : showLyric ? player.title + " · " + player.subtitle : player.error.isEmpty() ? player.subtitle : player.error);
                if (!playerPage) miniProgress.setProgress(progressOf(player.position(), player.duration()));
                if (!playerPage) updateMiniControls(player);
                if (lastSourcePlayer != player || lastSourceVersion != player.sourceStatusVersion) {
                    lastSourcePlayer = player;
                    lastSourceVersion = player.sourceStatusVersion;
                    sourceVisibleUntil = android.os.SystemClock.elapsedRealtime() + (player.sourceStatus.endsWith("成功") ? 5000 : 8500);
                }
                boolean showSource = !fullScreenLyrics() && !player.sourceStatus.isEmpty()
                    && (player.resolving || android.os.SystemClock.elapsedRealtime() < sourceVisibleUntil);
                sourceStatus.setVisibility(showSource ? View.VISIBLE : View.GONE);
                if (showSource) {
                    sourceStatus.setText(player.sourceFallback.isEmpty() ? player.sourceStatus : player.sourceStatus + "\n" + player.sourceFallback);
                    boolean failed = player.sourceStatus.endsWith("失败") || player.sourceStatus.endsWith("无可用音源")
                        || player.sourceStatus.endsWith("不完整") || player.sourceStatus.endsWith("需联网")
                        || player.sourceStatus.endsWith("缓存上限") || player.sourceStatus.endsWith("没有完整的离线缓存");
                    sourceStatus.setTextColor(failed ? ((playerPage ? playerDark() : darkAppearance) ? 0xffffd7a2 : AMBER)
                        : playerPage ? playerInk() : ACCENT);
                }
                if (playerPage && !tracking) {
                    if (displayedPlayer != player || shownRevision != player.revision()) refresh();
                    if (seek != null) updatePlayer(player);
                }
                if (queueDialog != null && (sheetVersion != player.queueVersion() || sheetIndex != player.index())) renderQueue(player);
            } else {
                main.removeCallbacks(hideMiniToggle);
                miniService = null;
                miniBitmap = null;
                sourceStatus.setVisibility(View.GONE);
                if (queueDialog != null) queueDialog.dismiss();
                if (effectsDialog != null) effectsDialog.dismiss();
                if (playerPage && displayedPlayer != null) refresh();
            }
            main.postDelayed(this, playerPage ? 225 : 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        sources = new MediaSources(this);
        prefs = sources.settings;
        applyAppearance(shouldUseDarkAppearance());
        mediaCache = MediaCache.get(this);
        playlists = PlaylistRefresh.get(this);
        prefs.registerOnSharedPreferenceChangeListener(entryCoverObserver);
        updates = SourceUpdates.get(this);
        updates.observe(updateObserver);
        root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, inset) -> {
            var safe = inset.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            if (android.os.Build.VERSION.SDK_INT >= 30) view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return inset;
        });
        if (android.os.Build.VERSION.SDK_INT < 30) {
            root.setFitsSystemWindows(true);
            root.setOnApplyWindowInsetsListener(null);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(root);
        screen = this::home;
        screen.run();
        if (getIntent().getBooleanExtra("show-player", false) && PlaybackService.active != null) openPlayer();
        main.post(tick);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("show-player", false) && !playerPage && PlaybackService.active != null) openPlayer();
    }
    @Override protected void onStart() {
        super.onStart();
        var clock = new IntentFilter(Intent.ACTION_TIME_TICK);
        clock.addAction(Intent.ACTION_TIME_CHANGED);
        clock.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(appearanceClock, clock, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(appearanceClock, clock);
        refreshAppearance(false);
        refreshHomeTemplate();
        playlists.schedule();
        int pageId = generation;
        playlists.foreground(report -> {
            if (report.updated() > 0 && current(pageId) && playlistScreen) refresh();
        });
        updates.schedule();
        updates.check(false, null);
    }
    @Override protected void onStop() {
        unregisterReceiver(appearanceClock);
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (bookDiscoveryRequest != null) bookDiscoveryRequest.cancel();
        sources.closeBookDiscovery();
        if (airPlayPicker != null) airPlayPicker.dismiss();
        stopLibraryDiscovery();
        updates.removeObserver(updateObserver);
        prefs.unregisterOnSharedPreferenceChangeListener(entryCoverObserver);
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        images.shutdownNow();
        if (queueDialog != null) queueDialog.dismiss();
        if (playerMenu != null) playerMenu.dismiss();
        if (effectsDialog != null) effectsDialog.dismiss();
        closeBrowser();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (fullScreenLyrics()) toggleFullScreenLyrics();
        else if (back.isEmpty()) super.onBackPressed();
        else {
            screen = back.pop();
            screen.run();
        }
    }
    private void open(Runnable next) { back.push(screen); screen = next; screen.run(); }
    private void refresh() { screen.run(); }
    private void homeOnly() { back.clear(); screen = this::home; screen.run(); }
    private Appearance appearance() { return Appearance.read(prefs.getString("appearance-mode", "LIGHT")); }
    private int appearanceStart(boolean dark) {
        return prefs.getInt(dark ? "appearance-dark-start" : "appearance-light-start",
            dark ? Appearance.DEFAULT_DARK : Appearance.DEFAULT_LIGHT);
    }
    private boolean shouldUseDarkAppearance() {
        if ("SYSTEM".equals(prefs.getString("appearance-mode", "LIGHT")))
            return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        var local = java.time.LocalTime.now();
        return appearance().darkAt(local.getHour() * 60 + local.getMinute(), appearanceStart(false), appearanceStart(true));
    }
    private void applyAppearance(boolean dark) {
        darkAppearance = dark;
        BG = dark ? 0xff111219 : 0xfff7f1ea;
        PAPER = dark ? 0xff20222b : 0xfffffbf6;
        INK = dark ? 0xfff6f4f2 : 0xff251b18;
        MUTED = dark ? 0xffb7bac6 : 0xff66564e;
        ACCENT = dark ? 0xffa4e7d7 : 0xffa9432f;
        ACCENT_TINT = dark ? 0xff243d3c : 0xfff4e1d7;
        SOFT = dark ? 0xff2a2d39 : 0xffede3da;
        ON_ACCENT = dark ? 0xff112e29 : Color.WHITE;
        RIPPLE = dark ? 0x32a4e7d7 : 0x25a9432f;
        String accent = prefs.getString("personal-accent", "original");
        if (!accent.equals("original")) {
            boolean green = accent.equals("green");
            ACCENT = green ? dark ? 0xff9bd5b4 : 0xff35694b : dark ? 0xffaac7ec : 0xff405f85;
            ACCENT_TINT = green ? dark ? 0xff20392c : 0xffe3eee5 : dark ? 0xff243348 : 0xffe4ebf5;
            RIPPLE = (ACCENT & 0xffffff) | 0x28000000;
            ON_ACCENT = dark ? 0xff11232f : Color.WHITE;
        }
        if (prefs.getString("personal-theme", "default").equals("transparent"))
            PAPER = (PAPER & 0xffffff) | 0xbb000000;
    }
    private void refreshAppearance(boolean force) {
        boolean dark = shouldUseDarkAppearance();
        if (!force && dark == darkAppearance) return;
        int scrollY = pageScroll == null ? 0 : pageScroll.getScrollY();
        applyAppearance(dark);
        refresh();
        var scroll = pageScroll;
        if (scroll != null && scrollY != 0) scroll.post(() -> scroll.scrollTo(0, scrollY));
    }
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, (playerPage ? playerDark() : darkAppearance)
            ? R.style.Theme_YinLang_Alert_Dark : R.style.Theme_YinLang_Alert);
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout column() { var layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout line() { var layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView text(String value, float size, int color, boolean bold) {
        var label = new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        label.setIncludeFontPadding(false);
        if (bold) label.setTypeface(Typeface.create(size >= 25 ? "sans-serif-black" : "sans-serif-medium", Typeface.NORMAL));
        return label;
    }
    private GradientDrawable shape(int color, int radius) {
        var background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }
    private GradientDrawable glass(boolean dark, int radius) {
        var surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR, dark
            ? new int[]{0x2fffffff, 0x15ffffff} : darkAppearance
                ? new int[]{0xff252b33, 0xff191e25} : new int[]{0xfafffffc, 0xfff0e5dd});
        surface.setCornerRadius(dp(radius));
        surface.setStroke(dp(1), dark || darkAppearance ? 0x36ffffff : 0xfff9f8f4);
        if (!dark && prefs.getString("personal-theme", "default").equals("transparent")) {
            surface.setColors(darkAppearance ? new int[]{0xbb343b48, 0x88222c39} : new int[]{0xbbffffff, 0x88e4ebf4});
            surface.setStroke(dp(1), darkAppearance ? 0x48ffffff : 0x85ffffff);
        }
        return surface;
    }
    private RippleDrawable ripple(int color, int radius, int touchColor) {
        return new RippleDrawable(ColorStateList.valueOf(touchColor), shape(color, radius), shape(PAPER, radius));
    }
    private void clickable(View view, int color, int radius, Runnable action) {
        view.setBackground(ripple(color, radius, RIPPLE));
        action(view, action);
    }
    private void action(View view, Runnable action) {
        view.setOnClickListener(ignored -> action.run());
        view.setFocusable(true);
    }
    private TextView button(String title, boolean primary, Runnable action) {
        TextView view = text(title, 14, primary ? ON_ACCENT : ACCENT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(15), dp(8), dp(15), dp(8));
        view.setMinHeight(dp(48));
        if (primary) clickable(view, ACCENT, PILL_RADIUS, action);
        else {
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), glass(false, PILL_RADIUS), shape(PAPER, PILL_RADIUS)));
            action(view, action);
        }
        return view;
    }
    private FrameLayout iconButton(UiIcon.Kind icon, String label, boolean dark, Runnable action) {
        return iconButton(icon, label, dark, 48, 20, true, action);
    }
    private FrameLayout iconButton(UiIcon.Kind icon, String label, boolean dark, int target, int glyph, boolean surface, Runnable action) {
        var button = new FrameLayout(this);
        button.addView(new UiIcon(this, icon, glyph, dark ? Color.WHITE : ACCENT), new FrameLayout.LayoutParams(-1, -1));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(target), dp(target)));
        button.setContentDescription(label);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x35ffffff : RIPPLE),
            surface ? glass(dark, target / 2) : null, shape(PAPER, target / 2)));
        action(button, action);
        return button;
    }
    private void iconState(FrameLayout button, UiIcon.Kind kind, int ink, String description) {
        var icon = (UiIcon) button.getChildAt(0);
        icon.setKind(kind);
        icon.setInk(ink);
        if (!description.contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription())) {
            button.setContentDescription(description);
        }
    }
    private TextView badge(String title, int color, int fillColor) {
        var label = text(title, 11, color, true);
        label.setPadding(dp(8), dp(4), dp(8), dp(4));
        label.setGravity(Gravity.CENTER);
        label.setBackground(shape(fillColor, 8));
        return label;
    }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void space(LinearLayout parent, int width) { parent.addView(new View(this), new LinearLayout.LayoutParams(dp(width), 1)); }
    private void fill(LinearLayout parent, View view) { parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); }
    private void chips(LinearLayout parent, LinearLayout controls) {
        var scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(controls);
        fill(parent, scroll);
    }
    private void weight(LinearLayout parent, View view) { parent.addView(view, new LinearLayout.LayoutParams(0, -2, 1)); }
    private void section(String heading) {
        gap(body, 18);
        TextView title = text(heading, 18, INK, true);
        title.setAccessibilityHeading(true);
        fill(body, title);
        gap(body, 9);
    }
    private void note(LinearLayout parent, String value) {
        TextView label = text(value, 14, MUTED, false);
        label.setLineSpacing(dp(4), 1);
        label.setPadding(0, dp(12), 0, dp(12));
        fill(parent, label);
    }
    private LinearLayout settingsCard(LinearLayout parent) {
        var card = column();
        card.setBackground(shape(PAPER, CARD_RADIUS));
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        fill(parent, card);
        return card;
    }
    private Switch settingSwitch(String label, boolean checked) {
        var toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(INK);
        toggle.setTextSize(15);
        toggle.setSwitchPadding(dp(12));
        toggle.setMinHeight(dp(48));
        toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACCENT, 0xff9d94a4}));
        toggle.setChecked(checked);
        return toggle;
    }
    private EditText labeledField(LinearLayout card, String label, String hint, String value, int inputType) {
        var input = field(hint, value, inputType);
        input.setId(View.generateViewId());
        setFieldSurface(input, BG, CARD_RADIUS);
        var caption = text(label, 13, MUTED, true);
        caption.setLabelFor(input.getId());
        fill(card, caption);
        gap(card, 6);
        fill(card, input);
        return input;
    }
    private void page(String eyebrow, String title) {
        page(eyebrow, title, false);
    }
    private void page(String eyebrow, String title, Runnable headerAction) {
        page(eyebrow, title, false, headerAction);
    }
    private void page(String eyebrow, String title, boolean night) {
        page(eyebrow, title, night, null);
    }
    private void page(String eyebrow, String title, boolean night, Runnable headerAction) {
        homeVisible = false;
        musicResumeContainer = null;
        musicResumeCaption = null;
        musicResumeProgress = null;
        if (bookDiscoveryRequest != null) { bookDiscoveryRequest.cancel(); bookDiscoveryRequest = null; }
        if (activeBookDiscovery != null && pageScroll != null) activeBookDiscovery.scroll = pageScroll.getScrollY();
        activeBookDiscovery = null;
        bookDiscoveryLoading = false;
        bookDiscoveryFilters = bookDiscoveryResults = bookDiscoveryFooter = null;
        main.removeCallbacks(hideMiniToggle);
        if (miniToggle != null) miniToggle.animate().cancel();
        activeCharts = null;
        refreshChartArtwork = false;
        stopLibraryDiscovery();
        closeBrowser();
        generation++;
        playerPage = night;
        android.transition.TransitionManager.endTransitions(root);
        if (playerMenu != null) playerMenu.dismiss();
        updateKeepScreenOn();
        int background = night ? playerBackground() : BG;
        root.setBackgroundColor(background);
        if (!night && prefs.getString("personal-theme", "default").equals("transparent"))
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, darkAppearance
                ? new int[]{0xff142631, 0xff302638, 0xff152c28}
                : new int[]{0xffe3edf4, 0xfff2e5ef, 0xffe0eee7}));
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(night && playerDark() ? 0xff14111a : background);
        getWindow().getDecorView().setSystemUiVisibility((night ? playerDark() : darkAppearance) ? 0 :
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        seek = null;
        playToggle = null;
        recordArt = null;
        playerArtwork = null;
        playerFavorite = null;
        lyricFavorite = null;
        airPlayControl = null;
        playerMode = null;
        lyricsScroll = null;
        lyricsControl = null;
        playerVolume = null;
        draggingVolume = false;
        backdrop = null;
        lyricTrack = null;
        playerMetadata = null;
        playerFooter = null;
        playerHandle = null;
        updateLabel = null;
        sourceRows = null;
        homeSettings = null;
        playlistScreen = false;
        bookArtwork = eyebrow.contains("有声书") || eyebrow.contains("听书");
        tracking = false;
        root.removeAllViews();
        var header = line();
        header.setPadding(dp(16), dp(12), dp(16), dp(14));
        if (!back.isEmpty() && !night) {
            header.addView(iconButton(UiIcon.Kind.BACK, "返回", false, this::onBackPressed));
            space(header, 12);
        }
        var heading = column();
        if (!eyebrow.isEmpty()) {
            var category = text(eyebrow, 11, MUTED, true);
            category.setLetterSpacing(0.06f);
            heading.addView(category);
            gap(heading, 4);
        }
        TextView headingTitle = text(title, back.isEmpty() ? 34 : 25, night ? Color.WHITE : INK, true);
        headingTitle.setAccessibilityHeading(true);
        headingTitle.setMaxLines(2);
        headingTitle.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(headingTitle);
        weight(header, heading);
        if (headerAction != null) header.addView(navidromeHeaderButton(headerAction));
        if (back.isEmpty() && headerAction == null) {
            homeSettings = iconButton(UiIcon.Kind.SETTINGS, "设置", false, 48, 28, false, () -> open(this::settings));
            homeSettings.removeAllViews();
            var gear = new ImageView(this);
            gear.setImageResource(R.drawable.ic_settings_cupertino);
            gear.setImageTintList(ColorStateList.valueOf(INK));
            gear.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            homeSettings.addView(gear, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            header.addView(homeSettings);
            showUpdateCount();
        }
        if (!night) root.addView(header);
        else {
            var dismiss = new FrameLayout(this);
            var handle = new View(this);
            handle.setBackground(shape(playerDark() ? 0x7cffffff : 0x70523e36, 4));
            dismiss.addView(handle, new FrameLayout.LayoutParams(dp(42), dp(4), Gravity.CENTER));
            dismiss.setContentDescription("收起播放器");
            clickable(dismiss, Color.TRANSPARENT, 14, this::onBackPressed);
            var grip = new LinearLayout.LayoutParams(dp(64), dp(48));
            grip.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(dismiss, grip);
            playerHandle = dismiss;
        }

        body = column();
        pageScroll = null;
        coverViewport = null;
        glassCards.clear();
        onlineEntryCards.clear();
        pendingChartCovers.clear();
        int contentPadding = dp(night ? 20 : 16);
        body.setPadding(contentPadding, dp(4), contentPadding, dp(night ? 6 : 22));
        var viewport = new LinearLayout.LayoutParams(-1, 0, 1);
        if (night) root.addView(body, viewport);
        else {
            var scroll = new ScrollView(this);
            pageScroll = scroll;
            scroll.setFillViewport(true);
            scroll.addView(body);
            coverViewport = new CoverViewport(scroll, y -> {
                if (scroll != pageScroll) return;
                for (FrostedCard card : glassCards) if (card.getParent() != null && card.getGlobalVisibleRect(visibleCard)) card.reflectScroll(y);
                if (activeBookDiscovery != null && y > 0) {
                    activeBookDiscovery.scroll = y;
                    maybeLoadBookDiscovery();
                }
            });
            root.addView(scroll, viewport);
        }
        sourceStatus = text("", 13, night ? playerInk() : ACCENT, true);
        sourceStatus.setMaxLines(2);
        sourceStatus.setEllipsize(TextUtils.TruncateAt.END);
        sourceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sourceStatus.setPadding(dp(15), dp(9), dp(15), dp(9));
        sourceStatus.setBackground(night ? playerSurface(CARD_RADIUS) : shape(ACCENT_TINT, CARD_RADIUS));
        var statusLayout = new LinearLayout.LayoutParams(-1, -2);
        statusLayout.setMargins(dp(16), 0, dp(16), dp(5));
        root.addView(sourceStatus, statusLayout);
        sourceStatus.setVisibility(View.GONE);
        mini = line();
        mini.setPadding(dp(8), dp(6), dp(8), dp(6));
        mini.setBackground(glass(false, 30));
        mini.setElevation(dp(6));
        miniCover = iconButton(UiIcon.Kind.PLAY, "播放", false, 48, 24, false, this::toggleMiniPlayback);
        miniToggleIcon = (UiIcon) miniCover.getChildAt(0);
        miniToggleIcon.setInk(Color.WHITE);
        miniCover.removeAllViews();
        miniCover.setBackground(shape(SOFT, 11));
        miniCover.setClipToOutline(true);
        miniCover.setForeground(ripple(Color.TRANSPARENT, 11, 0x35ffffff));
        miniCover.setOnLongClickListener(view -> { openPlayer(); return true; });
        miniArtwork = coverView(R.drawable.yinlang_neon);
        miniArtwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        miniCover.addView(miniArtwork, new FrameLayout.LayoutParams(-1, -1));
        miniToggle = new FrameLayout(this);
        miniToggle.setBackgroundColor(0x66000000);
        miniToggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        miniToggle.addView(miniToggleIcon, new FrameLayout.LayoutParams(-1, -1));
        miniCover.addView(miniToggle, new FrameLayout.LayoutParams(-1, -1));
        mini.addView(miniCover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        space(mini, 11);
        var miniLabels = column();
        miniTitle = text("", 15, INK, true);
        miniSubtitle = text("", 12, MUTED, false);
        for (TextView label : List.of(miniTitle, miniSubtitle)) {
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
        }
        miniLabels.addView(miniTitle);
        gap(miniLabels, 4);
        miniLabels.addView(miniSubtitle);
        gap(miniLabels, 5);
        miniProgress = progressLine(0, ACCENT, SOFT);
        miniLabels.addView(miniProgress, new LinearLayout.LayoutParams(-1, dp(3)));
        miniLabels.setContentDescription("展开播放器");
        miniLabels.setOnClickListener(view -> openPlayer());
        weight(mini, miniLabels);
        space(mini, 10);
        mini.addView(iconButton(UiIcon.Kind.QUEUE, "播放队列", false, 48, 21, false, () -> {
            if (PlaybackService.active != null) showQueue(PlaybackService.active);
        }));
        mini.setVisibility(night || PlaybackService.active == null ? View.GONE : View.VISIBLE);
        var miniLayout = new LinearLayout.LayoutParams(-1, -2);
        miniLayout.setMargins(dp(14), 0, dp(14), dp(10));
        root.addView(mini, miniLayout);
        if (!night && PlaybackService.active != null) updateMiniControls(PlaybackService.active);
    }

    private void updateMiniControls(PlaybackService player) {
        boolean changed = miniService != player || miniRevision != player.revision() || miniPageId != generation;
        boolean pause = player.wantsPause();
        miniToggleIcon.setKind(pause ? UiIcon.Kind.PAUSE : UiIcon.Kind.PLAY);
        miniCover.setContentDescription(pause ? "暂停" : "播放");
        if (changed) {
            miniService = player;
            miniRevision = player.revision();
            miniPageId = generation;
            miniBitmap = null;
            miniArtwork.setImageResource(player.book ? R.drawable.hero_books : R.drawable.yinlang_neon);
        }
        Bitmap bitmap = player.artwork();
        if (bitmap != null && bitmap != miniBitmap) {
            miniBitmap = bitmap;
            miniArtwork.setImageBitmap(bitmap);
        }
        if (changed || pause != miniWantsPause) showMiniToggle();
        miniWantsPause = pause;
    }

    private void toggleMiniPlayback() {
        PlaybackService player = PlaybackService.active;
        if (player == null) return;
        player.toggle();
        updateMiniControls(player);
        showMiniToggle();
    }

    private void showMiniToggle() {
        main.removeCallbacks(hideMiniToggle);
        miniToggle.animate().cancel();
        miniToggle.setAlpha(1f);
        // The cover remains the click target after the visual control fades out.
        main.postDelayed(hideMiniToggle, 2000);
    }

    private FrameLayout navidromeHeaderButton(Runnable action) {
        var button = iconButton(UiIcon.Kind.ALBUM, "音乐库连接与音质设置", false, 48, 20, false, action);
        button.removeAllViews();
        var logo = new ImageView(this);
        logo.setImageResource(R.drawable.navidrome_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(logo, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        return button;
    }
    private void home() {
        homeContent();
    }

    private void homeContent() {
        page("", "音浪");
        homeVisible = true;
        displayedHomeTemplate = currentHomeTemplate();
        if (displayedHomeTemplate != HomeTemplate.CLASSIC) {
            fill(body, text(displayedHomeTemplate == HomeTemplate.GALLERY ? "一首歌，一段故事。" : "此刻，听点喜欢的。", 13, MUTED, false));
            gap(body, 18);
            homeDesignedEntry(displayedHomeTemplate, false);
            gap(body, 14);
            homeDesignedEntry(displayedHomeTemplate, true);
        } else if (darkAppearance) {
            var pair = line();
            int height = Math.max(164, Math.min(238, (getResources().getConfiguration().screenWidthDp - 44) / 2 + 22));
            height += Math.max(0, (int) ((getResources().getConfiguration().fontScale - 1) * 55));
            pair.addView(homeDarkEntry("音乐", "歌单 · 榜单 · 音乐库", R.drawable.home_music_dark, this::music),
                new LinearLayout.LayoutParams(0, dp(height), 1));
            space(pair, 10);
            pair.addView(homeDarkEntry("有声书", "书架 · 发现 · 搜索", R.drawable.home_books_dark, this::audiobooks),
                new LinearLayout.LayoutParams(0, dp(height), 1));
            fill(body, pair);
        } else {
            homeLightEntry("音乐", "歌单 · 榜单 · 音乐库", R.drawable.home_music_light, false, this::music);
            gap(body, 10);
            homeLightEntry("有声书", "书架 · 发现 · 搜索", R.drawable.home_books_light, true, this::audiobooks);
        }
        gap(body, 14);
        row(body, "本机下载", "", "", this::offlineOpen);
    }

    private HomeTemplate currentHomeTemplate() {
        return HomeTemplate.resolve(prefs.getString(HomeTemplate.KEY, HomeTemplate.CLASSIC.name()),
            prefs.getString(HomeTemplate.ROTATION_KEY, "daily"), java.time.ZonedDateTime.now());
    }

    private void refreshHomeTemplate() {
        if (homeVisible && displayedHomeTemplate != currentHomeTemplate()) {
            int scroll = pageScroll == null ? 0 : pageScroll.getScrollY();
            refresh();
            if (pageScroll != null) pageScroll.scrollTo(0, scroll);
        }
    }

    private void homeDesignedEntry(HomeTemplate template, boolean books) {
        boolean gallery = template == HomeTemplate.GALLERY;
        String title = books ? "有声书" : "音乐";
        int ink = gallery ? darkAppearance ? 0xffeef2eb : 0xff243e34 : Color.WHITE;
        int secondary = gallery ? darkAppearance ? 0xffb9c6be : 0xff63766b : 0xffe4eeed;
        if (gallery && books) { ink = darkAppearance ? 0xfff5e8dd : 0xff513a2e; secondary = darkAppearance ? 0xffd0b9a7 : 0xff8a705d; }
        int[] colors = gallery ? books ? darkAppearance ? new int[]{0xff3b2f2b, 0xff312a29} : new int[]{0xfff2e8dc, 0xffeee0cd}
                : darkAppearance ? new int[]{0xff243c34, 0xff24332f} : new int[]{0xffe9eee7, 0xffdce7db}
            : books ? darkAppearance ? new int[]{0xff663951, 0xffa15a4b} : new int[]{0xff854256, 0xffbc7057}
                : darkAppearance ? new int[]{0xff173d53, 0xff216966} : new int[]{0xff204966, 0xff2b7c77};
        var card = new FrameLayout(this);
        var surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        surface.setCornerRadius(dp(24));
        card.setBackground(surface);
        card.setForeground(new RippleDrawable(ColorStateList.valueOf(gallery ? RIPPLE : 0x25ffffff), null, shape(Color.WHITE, 24)));
        card.setClipToOutline(true);
        card.setContentDescription(title + (books ? "，书架、发现、搜索" : "，歌单、榜单、音乐库"));
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        action(card, () -> open(books ? this::audiobooks : this::music));
        int width = getResources().getConfiguration().screenWidthDp - 32;
        var art = new HomeEntryArtwork(this, template, books, darkAppearance);
        card.addView(art, new FrameLayout.LayoutParams(dp(width * (gallery ? .46f : .55f)), -1, Gravity.RIGHT));
        var names = column();
        names.setGravity(Gravity.CENTER_VERTICAL);
        names.setPadding(dp(22), dp(20), dp(4), dp(20));
        var kicker = text(gallery ? books ? "02 / 故事时光" : "01 / 旋律日常" : books ? "STORY CHANNEL" : "MUSIC CHANNEL", 10, secondary, true);
        kicker.setLetterSpacing(.08f);
        fill(names, kicker);
        gap(names, 10);
        fill(names, text(title, gallery ? 29 : 32, ink, true));
        gap(names, 8);
        var detail = text(books ? "书架 · 发现 · 搜索" : "歌单 · 榜单 · 音乐库", 12, secondary, false);
        detail.setMaxLines(2);
        fill(names, detail);
        gap(names, 22);
        var enter = line();
        enter.addView(text(books ? "走进故事" : "听见好音乐", 12, ink, true));
        fill(names, enter);
        var placement = new FrameLayout.LayoutParams(dp(width * .62f), -1);
        card.addView(names, placement);
        float font = getResources().getConfiguration().fontScale;
        body.addView(card, new LinearLayout.LayoutParams(-1, dp(Math.max(200, Math.min(232, width * .58f)) + Math.max(0, font - 1) * 115)));
    }

    private Bitmap homeBitmap(int resource) {
        Bitmap bitmap = homeArt.get(resource);
        if (bitmap != null) return bitmap;
        bitmap = BitmapFactory.decodeResource(getResources(), resource);
        if (bitmap != null) homeArt.put(resource, bitmap);
        return bitmap;
    }

    private void homeLightEntry(String title, String subtitle, int artwork, boolean artOnLeft, Runnable destination) {
        var card = new FrameLayout(this);
        clickable(card, artOnLeft ? 0xff39251a : 0xff152823, 20, () -> open(destination));
        card.setClipToOutline(true);
        var image = new ImageView(this);
        image.setImageBitmap(homeBitmap(artwork));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        var scrim = new View(this);
        scrim.setBackground(new GradientDrawable(artOnLeft ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0x95110c08, 0x50110c08, 0x00110c08}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        var labels = line();
        var names = column();
        names.setGravity(Gravity.CENTER_VERTICAL);
        names.setPadding(dp(16), dp(7), dp(10), dp(7));
        var heading = text(title, 28, Color.WHITE, true);
        heading.setShadowLayer(dp(3), 0, dp(1), 0xaa110c08);
        fill(names, heading);
        gap(names, 5);
        fill(names, text(subtitle, 11, 0xfff8efe5, false));
        if (artOnLeft) labels.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        labels.addView(names, new LinearLayout.LayoutParams(0, -1, 1));
        if (!artOnLeft) labels.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        card.addView(labels, new FrameLayout.LayoutParams(-1, -1));
        int width = getResources().getConfiguration().screenWidthDp - 32;
        float font = getResources().getConfiguration().fontScale;
        body.addView(card, new LinearLayout.LayoutParams(-1, dp(Math.max(154, Math.min(214, width * .46f)) + Math.max(0, font - 1) * 70)));
    }

    private FrostedCard homeDarkEntry(String title, String subtitle, int artwork, Runnable destination) {
        var card = new FrostedCard(this, PAPER, title.hashCode(), 19);
        card.setArtwork(homeBitmap(artwork));
        card.shadeCaption(0xdf11151e);
        glassCards.add(card);
        action(card, () -> open(destination));
        var names = column();
        names.setPadding(dp(10), dp(8), dp(10), dp(9));
        fill(names, text(title, 22, Color.WHITE, true));
        gap(names, 4);
        fill(names, text(subtitle, 11, 0xffeeeaf1, false));
        var placement = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        placement.setMargins(dp(7), 0, dp(7), dp(7));
        card.addView(names, placement);
        return card;
    }

    private void homeResume(JSONObject book) {
        String source = book.optString("_source"), url = book.optString("bookUrl");
        if (source.isEmpty() || url.isEmpty()) return;
        String title = MediaEntry.first(book, "title", "name"), image = book.optString("coverUrl");
        String bookKey = source + "-" + url;
        String chapter = prefs.getString("last-book-chapter-" + bookKey, "");
        String audio = prefs.getString("last-book-" + bookKey, "");
        var progress = getSharedPreferences("progress", MODE_PRIVATE);
        int elapsed = progress.getInt(source + ":" + audio, 0), duration = progress.getInt(source + ":" + audio + ":duration", 0);
        String detail = chapter.isEmpty() ? "从上次的章节继续" : chapter;
        if (elapsed > 0 && duration > elapsed) detail += " · 剩余 " + Math.max(1, (duration - elapsed + 59_999) / 60_000) + " 分钟";
        Runnable resume = () -> resumeBook(source, book);
        resumeCard(body, "继续收听", title, detail, image, true, elapsed, duration, resume);
    }

    private record ResumeCardViews(ProgressBar progress, TextView caption) {}

    private ResumeCardViews resumeCard(LinearLayout target, String label, String title, String detail, String image, boolean audiobook,
                            int elapsed, int duration, Runnable resume) {
        var content = line();
        content.setPadding(dp(10), dp(9), dp(10), dp(9));
        if (!darkAppearance) {
            content.addView(art(image, 58, audiobook, audiobook ? R.drawable.hero_books : R.drawable.hero_music, ignored -> {}),
                new LinearLayout.LayoutParams(dp(58), dp(58)));
            space(content, 10);
        }
        var names = column();
        if (darkAppearance) { fill(names, text(label, 12, 0xffd8e5ed, true)); gap(names, 5); }
        var name = text(title, darkAppearance ? 24 : 17, darkAppearance ? Color.WHITE : INK, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        fill(names, name);
        gap(names, 4);
        var caption = text(detail, 12, darkAppearance ? 0xffdedde8 : MUTED, false);
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        fill(names, caption);
        ProgressBar progress = null;
        if (duration > 0) {
            gap(names, 8);
            progress = progressLine(progressOf(elapsed, duration), ACCENT, darkAppearance ? 0x58ffffff : SOFT);
            names.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        }
        weight(content, names);
        space(content, 8);
        var play = iconButton(UiIcon.Kind.PLAY, label + " " + title, darkAppearance, 48, 23, false, resume);
        iconState(play, UiIcon.Kind.PLAY, darkAppearance ? Color.WHITE : ACCENT, label + " " + title);
        content.addView(play);
        if (!darkAppearance) {
            clickable(content, PAPER, 16, resume);
            fill(target, content);
            return new ResumeCardViews(progress, caption);
        }
        var card = new FrostedCard(this, 0xff25243a, title.hashCode(), 20);
        card.setArtwork(homeBitmap(audiobook ? R.drawable.home_books_dark : R.drawable.home_music_dark));
        card.shadeCaption(0xe7121520);
        glassCards.add(card);
        action(card, resume);
        var position = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        position.setMargins(dp(8), 0, dp(8), dp(8));
        card.addView(content, position);
        target.addView(card, new LinearLayout.LayoutParams(-1, dp(187 + Math.max(0, getResources().getConfiguration().fontScale - 1) * 48)));
        loadCardCover(card, image, 240, audiobook);
        return new ResumeCardViews(progress, caption);
    }

    private MusicResume recentMusic() {
        var player = PlaybackService.active;
        MusicResume recent = player == null ? null : player.musicContinuation();
        if (recent != null && recent.available(sources.navCacheKey())) return recent;
        return MusicResume.read(prefs.getString(MusicResume.KEY, ""), prefs.getInt(MusicResume.POSITION_KEY, 0),
            prefs.getInt(MusicResume.DURATION_KEY, 0), sources.navCacheKey());
    }

    private void renderMusicResume() {
        musicResumePlayer = PlaybackService.active;
        musicResumeRevision = musicResumePlayer == null ? -1 : musicResumePlayer.revision();
        musicResumeDuration = musicResumePlayer == null || musicResumePlayer.book ? 0 : musicResumePlayer.duration();
        musicResumeCaption = null;
        musicResumeProgress = null;
        musicResumeContainer.removeAllViews();
        MusicResume recent = recentMusic();
        if (recent == null) return;
        gap(musicResumeContainer, 22);
        fill(musicResumeContainer, text("继续听歌", 20, INK, true));
        gap(musicResumeContainer, 12);
        var views = resumeCard(musicResumeContainer, "继续听歌", recent.entry().title(), musicResumeDetail(recent), coverAddress(recent.entry()), false,
            recent.position(), recent.duration(), () -> resumeMusic(recent));
        musicResumeCaption = views.caption();
        musicResumeProgress = views.progress();
    }

    private static String musicResumeDetail(MusicResume recent) {
        String detail = recent.entry().subtitle();
        if (recent.duration() > 0) detail += (detail.isBlank() ? "" : " · ") + time(recent.position()) + " / " + time(recent.duration());
        return detail.isBlank() ? "接着上次的旋律" : detail;
    }

    private void resumeMusic(MusicResume recent) {
        if (!recent.available(sources.navCacheKey())) { toast("音乐库账号已变更，请从当前音乐库选择歌曲"); return; }
        var player = PlaybackService.active;
        if (player != null && !player.book && player.index() >= 0) {
            var entry = player.entries().get(player.index());
            if (entry.source().equals(recent.entry().source()) && entry.id().equals(recent.entry().id())) {
                player.resume();
                openPlayer();
                return;
            }
        }
        PlaybackService.continueMusic(this, recent);
    }

    private static int progressOf(int elapsed, int duration) {
        return duration <= 0 ? 0 : (int) Math.min(1000, 1000L * Math.max(0, elapsed) / duration);
    }

    private ProgressBar progressLine(int progress, int color, int track) {
        var line = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        var fill = new ClipDrawable(shape(color, 2), Gravity.START, ClipDrawable.HORIZONTAL);
        var drawables = new LayerDrawable(new android.graphics.drawable.Drawable[]{shape(track, 2), fill});
        drawables.setId(0, android.R.id.background);
        drawables.setId(1, android.R.id.progress);
        line.setProgressDrawable(drawables);
        line.setMax(1000);
        line.setProgress(progress);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }
    private void music() {
        page("音乐", "发现音乐");
        searchEntry("搜索在线音乐或资料库", () -> open(() -> musicSearch(false)));
        section("在线音乐");
        var charts = musicEntry("在线排行榜", "五个平台的榜单", BERRY, () -> open(this::charts));
        var onlineLists = musicEntry("在线歌单", "按来源和分类浏览", GOLD, () -> open(this::playlists));
        modulePair(charts, onlineLists);
        var followed = musicEntry("关注的歌单", "", CALM_TEAL, () -> open(this::following));
        var liked = musicEntry("我喜欢的音乐", "", PLUM, () -> open(this::likedSongs));
        modulePair(followed, liked);
        section("个人音乐库");
        var local = musicEntry("本地歌单", "Navidrome 歌单", OCEAN, () -> open(this::localLists));
        var library = musicEntry("音乐库", "浏览 Navidrome", SLATE, () -> open(this::library));
        modulePair(local, library);
        musicResumeContainer = column();
        fill(body, musicResumeContainer);
        renderMusicResume();
        row(body, "音乐设置", "LX 音源与优先音质", "", () -> open(this::musicSettings));

        bindOnlineEntry(charts, PlaylistRefresh.DISCOVERY_CHARTS, this::seedChartEntryCover);
        bindOnlineEntry(onlineLists, PlaylistRefresh.DISCOVERY_LISTS, this::seedPlaylistEntryCover);
        var following = playlists.following();
        var followingCovers = following.stream().map(ChartArtwork::address).filter(url -> !url.isEmpty()).distinct()
            .collect(Collectors.toList());
        if (followingCovers.isEmpty() && !following.isEmpty()) {
            int page = generation;
            whenEntryVisible(followed, () -> loadFollowedEntryCover(followed, following, 0, page));
        } else loadMusicEntryCover(followed, followingCovers);
        loadMusicEntryCover(liked, likedEntries().stream().filter(entry -> !entry.cover().isEmpty()).limit(6)
            .map(this::coverAddress).filter(url -> !url.isEmpty()).distinct().collect(Collectors.toList()));
        if (!prefs.getString("nav-url", "").isBlank()) {
            loadLibraryEntryCover(local, this::localPlaylistArtwork);
            loadLibraryEntryCover(library, playlists::navDiscoveryAlbums);
        }
    }

    private FrostedCard musicEntry(String title, String subtitle, int color, Runnable select) {
        var card = new FrostedCard(this, color, title.hashCode(), CARD_RADIUS);
        card.shadeCaption(darkAppearance ? 0xee11131c : 0xe823181a);
        glassCards.add(card);
        action(card, select);
        var labels = column();
        labels.setPadding(dp(12), dp(5), dp(10), dp(12));
        var heading = text(title, 16, Color.WHITE, true);
        heading.setShadowLayer(dp(3), 0, dp(1), 0xda181217);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        fill(labels, heading);
        if (!subtitle.isEmpty()) {
            gap(labels, 4);
            var caption = text(subtitle, 12, 0xfff4edef, false);
            caption.setShadowLayer(dp(2), 0, dp(1), 0xcf181217);
            caption.setSingleLine();
            caption.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, caption);
        }
        card.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return card;
    }

    private void bindOnlineEntry(FrostedCard card, String key, Runnable seed) {
        onlineEntryCards.put(key, card);
        List<String> covers = playlists.discoveryCovers(key);
        if (covers.isEmpty()) whenEntryVisible(card, seed);
        else loadMusicEntryCover(card, covers);
    }

    private void seedPlaylistEntryCover() {
        var options = listOptions(1);
        playlists.cachedOnline("lists", options, (value, error) -> {
            var covers = ChartArtwork.catalog(value);
            if (!covers.isEmpty()) {
                playlists.rememberDiscoveryCovers(PlaylistRefresh.DISCOVERY_LISTS, covers, false);
                return;
            }
            if (!hasOnlineNetwork() || onlineEntryCards.get(PlaylistRefresh.DISCOVERY_LISTS) == null
                || !playlists.discoveryCovers(PlaylistRefresh.DISCOVERY_LISTS).isEmpty()) return;
            String source = options.optString("source");
            var seed = "all".equals(source) ? MediaSources.args("source", "wy", "sort", "@hot") : options;
            playlists.online("lists", seed, true, (result, failure) ->
                playlists.rememberDiscoveryCovers(PlaylistRefresh.DISCOVERY_LISTS, ChartArtwork.catalog(result), false));
        });
    }

    private void seedChartEntryCover() {
        if (chartEntryLoading) return;
        String source = chartPlatform;
        int request = ++chartEntryRequest;
        chartEntryLoading = true;
        readArtworkCatalog("charts", MediaSources.args("source", source), (value, error) -> {
            if (value instanceof JSONObject data) resolveChartEntryCover(source, data, false, request);
            else finishChartEntryCover(List.of(), false, request);
        });
    }

    private void resolveChartEntryCover(String source, JSONObject data, boolean rotate, int request) {
        if (request != chartEntryRequest || isDestroyed()) return;
        var ids = objects(array(data, "list")).stream().map(board -> MediaEntry.first(board, "bangid", "id"))
            .filter(id -> !id.isEmpty()).distinct().limit(4).collect(Collectors.toList());
        if (ids.isEmpty()) {
            finishChartEntryCover(List.of(), rotate, request);
            return;
        }
        playlists.cachedChartTrackCovers(source, ids, cached -> {
            if (request != chartEntryRequest || isDestroyed()) return;
            if (cached.size() >= 2 || !hasOnlineNetwork()) {
                finishChartEntryCover(cached, rotate, request);
                return;
            }
            String id = ids.get(cached.isEmpty() || ids.size() == 1 ? 0 : 1);
            playlists.online("chart-cover", MediaSources.args("source", source, "id", id), true, (value, error) -> {
                var covers = new java.util.LinkedHashSet<>(cached);
                covers.addAll(ChartArtwork.topTrackCover(value));
                finishChartEntryCover(List.copyOf(covers), rotate, request);
            });
        });
    }

    private void finishChartEntryCover(List<String> covers, boolean rotate, int request) {
        if (request != chartEntryRequest || isDestroyed()) return;
        chartEntryLoading = false;
        playlists.rememberDiscoveryCovers(PlaylistRefresh.DISCOVERY_CHARTS, covers, rotate);
    }

    private void readArtworkCatalog(String operation, JSONObject options, ScriptRuntime.Callback callback) {
        if (hasOnlineNetwork()) playlists.online(operation, options, true, callback);
        else playlists.cachedOnline(operation, options, callback);
    }

    private void whenEntryVisible(FrostedCard card, Runnable load) {
        if (coverViewport != null) coverViewport.loadWhenVisible(card, load);
        else load.run();
    }

    private void loadMusicEntryCover(FrostedCard card, List<String> covers) {
        if (covers.isEmpty() || covers.equals(card.getTag())) return;
        card.setTag(covers);
        int page = generation;
        whenEntryVisible(card, () -> tryMusicEntryCover(card, covers, 0, page));
    }

    private void tryMusicEntryCover(FrostedCard card, List<String> covers, int index, int page) {
        if (!current(page) || !card.isAttachedToWindow() || card.getTag() != covers || index >= Math.min(6, covers.size())) return;
        int edge = CoverThumbnail.edge(Math.min(320, dp(136)));
        String address = covers.get(index);
        requestCover(card, address, edge, false, true, bitmap -> {
            if (!current(page) || card.getTag() != covers) return;
            if (bitmap == null) {
                if (index + 1 < Math.min(6, covers.size())) {
                    whenEntryVisible(card, () -> tryMusicEntryCover(card, covers, index + 1, page));
                }
                return;
            }
            card.setArtwork(bitmap);
            onlineEntryCards.forEach((key, entry) -> {
                if (entry == card) playlists.noteDiscoveryCoverShown(key, address);
            });
        });
    }

    private void loadFollowedEntryCover(FrostedCard card, List<JSONObject> followed, int index, int page) {
        if (!current(page)) return;
        if (index >= Math.min(followed.size(), 6)) {
            if (!hasOnlineNetwork()) return;
            JSONObject first = followed.get(0);
            String source = first.optString("source"), id = first.optString("id"), title = first.optString("title");
            playlists.online("list", MediaSources.args("source", source, "id", id), true, (value, error) -> {
                var covers = ChartArtwork.fromDetail(value);
                if (!covers.isEmpty() && playlists.followed(source, id)) {
                    playlists.remember(source, id, title, null, covers.get(0));
                    if (current(page)) loadMusicEntryCover(card, covers);
                }
            });
            return;
        }
        JSONObject item = followed.get(index);
        var options = MediaSources.args("source", item.optString("source"), "id", item.optString("id"));
        playlists.cachedOnline("list", options, (value, error) -> {
            if (!current(page)) return;
            var covers = ChartArtwork.fromDetail(value);
            if (covers.isEmpty()) loadFollowedEntryCover(card, followed, index + 1, page);
            else loadMusicEntryCover(card, covers);
        });
    }

    private JSONArray localPlaylistArtwork() throws Exception {
        JSONArray lists = playlists.navLists(false);
        String firstWithSongs = "";
        for (JSONObject list : objects(lists)) {
            if (!list.optString("coverArt").isBlank()) return lists;
            if (firstWithSongs.isEmpty() && list.optInt("songCount") > 0) firstWithSongs = list.optString("id");
        }
        return firstWithSongs.isEmpty() ? lists : playlists.navList(firstWithSongs, false);
    }

    private void loadLibraryEntryCover(FrostedCard card, Work collection) {
        int page = generation;
        String account = sources.navCacheKey();
        whenEntryVisible(card, () -> work(collection, (value, error) -> {
            if (!current(page) || !account.equals(sources.navCacheKey()) || !(value instanceof JSONArray rows)) return;
            var covers = objects(rows).stream().map(item -> item.optString("coverArt"))
                .filter(id -> !id.isBlank()).distinct().limit(6).map(id -> libraryCover(id, 320)).collect(Collectors.toList());
            loadMusicEntryCover(card, covers);
        }));
    }

    private void audiobooks() {
        page("有声书", "听书");
        searchEntry("搜索有声书", () -> open(this::bookSearch));
        gap(body, 12);
        var shortcuts = column();
        shortcuts.addView(libraryShortcut("书架", UiIcon.Kind.BOOK, () -> open(this::bookshelf), null));
        gap(shortcuts, 8);
        shortcuts.addView(libraryShortcut("插件", UiIcon.Kind.PLUGIN, () -> open(this::bookPlugins), null));
        gap(shortcuts, 8);
        shortcuts.addView(libraryShortcut("听书设置", UiIcon.Kind.SETTINGS, () -> open(this::bookSettings), null));
        var navigation = line();
        navigation.setGravity(Gravity.TOP);
        navigation.addView(bookDiscoveryCard(), new LinearLayout.LayoutParams(0, -1, 1.3f));
        space(navigation, 10);
        navigation.addView(shortcuts, new LinearLayout.LayoutParams(0, -2, 1));
        fill(body, navigation);
        JSONObject recent = sources.registered("recent-books").optJSONObject(0);
        if (recent != null) { section("继续收听"); homeResume(recent); }
        section("我的收藏");
        List<JSONObject> shelf = objects(sources.registered("shelf"));
        if (shelf.isEmpty()) note(body, "在书籍详情里加入书架，喜欢的故事会出现在这里。");
        else bookGrid(body, shelf.subList(0, Math.min(6, shelf.size())), "");
        ensureBundledBooks();
    }

    private FrameLayout bookDiscoveryCard() {
        var card = new FrameLayout(this);
        card.setBackground(shape(ACCENT, 18));
        card.setClipToOutline(true);
        var art = new ImageView(this);
        art.setImageResource(R.drawable.hero_books);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        var shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x080f1723, 0xe51a2831}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        var labels = column();
        labels.setPadding(dp(16), dp(16), dp(16), dp(20));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        fill(labels, text("发现", 26, Color.WHITE, true));
        gap(labels, 6);
        var caption = line();
        weight(caption, text("听书 · 广播 · 播客", 12, 0xeeffffff, false));
        var arrow = new UiIcon(this, UiIcon.Kind.ARROW_RIGHT, 18, Color.WHITE);
        arrow.setRotation(-45);
        caption.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(24)));
        fill(labels, caption);
        card.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setContentDescription("发现，浏览听书、广播和播客");
        card.setForeground(new RippleDrawable(ColorStateList.valueOf(0x35ffffff), null, shape(Color.WHITE, 18)));
        action(card, () -> open(this::discover));
        return card;
    }

    private void ensureBundledBooks() {
        if (bundledBooksLoading || prefs.getBoolean("bundled-books-" + MediaSources.SSTS_VERSION, false)) return;
        bundledBooksLoading = true;
        int pageId = generation;
        sources.bundledBooks((value, error) -> {
            bundledBooksLoading = false;
            if (error != null) { toast("内置听书源更新失败：" + error); return; }
            prefs.edit().putBoolean("bundled-books-" + MediaSources.SSTS_VERSION, true).apply();
            if (value != null) {
                bookDiscoveries.evictAll();
                toast("听书源已更新至 " + MediaSources.SSTS_VERSION);
                if (current(pageId)) refresh();
            }
        });
    }

    private void bookSourcesChanged(String key) {
        if (key != null && (key.equals("book-plugins") || key.startsWith("book-enable-")
            || key.startsWith("book-pref-"))) {
            sources.closeBookDiscovery();
            main.post(() -> { if (activeBookDiscovery != null && !isDestroyed()) refresh(); });
        }
    }
    private void modulePair(View left, View right) {
        var strip = line();
        int height = dp(Math.max(96, 96 * getResources().getConfiguration().fontScale));
        strip.addView(left, new LinearLayout.LayoutParams(0, height, 1));
        space(strip, 8);
        strip.addView(right, new LinearLayout.LayoutParams(0, height, 1));
        fill(body, strip);
        gap(body, 8);
    }
    private FrameLayout moduleCard(String title, String subtitle, int color, UiIcon.Kind icon, boolean featured, Runnable action) {
        return discoveryCard(title, subtitle, "", color, featured, new UiIcon(this, icon, featured ? 108 : 63, 0x90ffffff), action);
    }
    private void searchEntry(String title, Runnable action) {
        var entry = line();
        entry.setPadding(dp(11), dp(2), dp(12), dp(2));
        entry.setMinimumHeight(dp(48));
        entry.addView(new UiIcon(this, UiIcon.Kind.SEARCH, 18, ACCENT), new LinearLayout.LayoutParams(dp(29), dp(40)));
        space(entry, 6);
        weight(entry, text(title, 14, MUTED, false));
        entry.addView(new UiIcon(this, UiIcon.Kind.NEXT, 16, MUTED), new LinearLayout.LayoutParams(dp(19), dp(30)));
        clickable(entry, PAPER, PILL_RADIUS, action);
        fill(body, entry);
    }
    private void row(LinearLayout parent, String title, String subtitle, String cover, Runnable action) {
        row(parent, title, subtitle, cover, rowIcon(title), action);
    }

    private void row(LinearLayout parent, String title, String subtitle, String cover, UiIcon.Kind fallbackIcon, Runnable action) {
        row(parent, title, subtitle, cover, fallbackIcon, false, action);
    }

    private void row(LinearLayout parent, String title, String subtitle, String cover, UiIcon.Kind fallbackIcon, boolean catalog, Runnable action) {
        row(parent, title, subtitle, cover, fallbackIcon, catalog, null, action);
    }

    private void row(LinearLayout parent, String title, String subtitle, String cover, UiIcon.Kind fallbackIcon,
                     boolean catalog, View trailing, Runnable action) {
        var item = line();
        item.setPadding(dp(11), dp(9), dp(11), dp(9));
        item.setMinimumHeight(dp(54));
        if (!cover.trim().isEmpty()) item.addView(catalog ? catalogArt(cover, 44) : art(cover, 44), new LinearLayout.LayoutParams(dp(44), dp(44)));
        else {
            var icon = new UiIcon(this, fallbackIcon, 19, ACCENT);
            icon.setInk(ACCENT);
            icon.setBackground(glass(false, 11));
            item.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        }
        space(item, 12);
        var labels = column();
        TextView name = text(title.trim().isEmpty() ? "未命名" : title, 15, INK, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        if (!subtitle.trim().isEmpty()) {
            gap(labels, 5);
            TextView secondary = text(subtitle, 13, MUTED, false);
            secondary.setMaxLines(2);
            secondary.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(secondary);
        }
        weight(item, labels);
        space(item, 10);
        if (trailing != null) item.addView(trailing);
        else item.addView(new UiIcon(this, UiIcon.Kind.NEXT, 15, MUTED), new LinearLayout.LayoutParams(dp(18), dp(28)));
        clickable(item, PAPER, CARD_RADIUS, action);
        fill(parent, item);
        gap(parent, 8);
    }
    private UiIcon.Kind rowIcon(String title) {
        if (title.contains("搜索")) return UiIcon.Kind.SEARCH;
        if (title.contains("下载")) return UiIcon.Kind.DOWNLOAD;
        if (title.contains("缓存")) return UiIcon.Kind.STORAGE;
        if (title.contains("服务器")) return UiIcon.Kind.SERVER;
        if (title.contains("更新")) return UiIcon.Kind.REFRESH;
        if (title.contains("设置")) return UiIcon.Kind.SETTINGS;
        if (title.contains("插件") || title.contains("音源")) return UiIcon.Kind.PLUGIN;
        if (title.contains("歌单")) return UiIcon.Kind.LIST;
        if (title.contains("发现")) return UiIcon.Kind.DISCOVER;
        return bookArtwork ? UiIcon.Kind.BOOK : UiIcon.Kind.ALBUM;
    }
    private ImageView art(String url, int size) {
        return art(url, size, bookArtwork);
    }
    private ImageView art(String url, int size, boolean book) {
        return art(url, size, book, bitmap -> {});
    }
    private ImageView art(String url, int size, boolean book, java.util.function.Consumer<Bitmap> loaded) {
        return art(url, size, book, 0, loaded);
    }
    private ImageView art(String url, int size, boolean book, int placeholder, java.util.function.Consumer<Bitmap> loaded) {
        var view = coverView(placeholder);
        loadCover(view, url, Math.max(120, dp(size) * 2), book, false, loaded);
        return view;
    }
    private ImageView catalogArt(String url, int size) {
        return catalogArt(url, size, ignored -> {});
    }

    private ImageView catalogArt(String url, int size, java.util.function.Consumer<Bitmap> loaded) {
        var view = coverView(0);
        Runnable load = () -> loadCover(view, url, CoverThumbnail.edge(dp(size)), false, true, loaded);
        if (coverViewport != null) coverViewport.loadWhenVisible(view, load);
        else load.run();
        return view;
    }
    private ImageView coverView(int placeholder) {
        var view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(shape(SOFT, 10));
        view.setClipToOutline(true);
        if (placeholder != 0) view.setImageResource(placeholder);
        return view;
    }
    private record CoverTarget(View view, java.util.function.Consumer<Bitmap> loaded, boolean visibleOnly, int page) {}

    private void loadCover(ImageView view, String url, int edge, boolean book, boolean thumbnail, java.util.function.Consumer<Bitmap> loaded) {
        requestCover(view, url, edge, book, thumbnail, bitmap -> {
            if (bitmap != null) { view.setImageBitmap(bitmap); loaded.accept(bitmap); }
        });
    }

    private void requestCover(View view, String url, int edge, boolean book, boolean thumbnail, java.util.function.Consumer<Bitmap> loaded) {
        String trimmed = url.trim();
        String address = trimmed.startsWith("//") ? "https:" + trimmed : trimmed;
        if (!(address.startsWith("https://") || address.startsWith("http://")) || images.isShutdown()) { loaded.accept(null); return; }
        String identity = coverIdentity(address);
        String key = thumbnail ? "cover-thumb:" + edge + ":" + identity : "cover:" + identity;
        var bucket = mediaCache.bucket(book);
        long revision = bucket.revision();
        String memory = book + ":" + revision + ":" + edge + ":" + key;
        Bitmap cached = mediaCache.limitMb(book) == 0 ? null : covers.get(memory);
        if (cached != null) { loaded.accept(cached); return; }
        var target = new CoverTarget(view, loaded, thumbnail, generation);
        List<CoverTarget> pending = pendingCovers.get(memory);
        if (pending != null) { pending.add(target); return; }
        Long failed = recentCoverFailures.get(memory);
        if (failed != null && android.os.SystemClock.elapsedRealtime() - failed < 60_000) { loaded.accept(null); return; }
        pendingCovers.put(memory, new ArrayList<>(List.of(target)));
        images.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeCover(bucket.read(key), edge);
                boolean diskHit = bitmap != null;
                if (bitmap == null && thumbnail) bitmap = shrinkCover(decodeCover(bucket.read("cover:" + identity), edge), edge);
                if (bitmap == null && hasOnlineNetwork()) {
                    if (thumbnail) bitmap = downloadThumbnail(address, edge);
                    else {
                        byte[] data = downloadCover(address);
                        bitmap = decodeCover(data, edge);
                        if (bitmap != null) bucket.write(key, data, revision);
                    }
                }
                if (thumbnail && bitmap != null && !diskHit) {
                    bitmap = shrinkCover(bitmap, edge);
                    if (mediaCache.limitMb(book) > 0) {
                        var output = new ByteArrayOutputStream();
                        if (bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, output)) bucket.write(key, output.toByteArray(), revision);
                    }
                }
            } catch (Exception ignored) {}
            Bitmap result = bitmap;
            main.post(() -> {
                var targets = pendingCovers.remove(memory);
                if (isDestroyed() || targets == null) return;
                if (result == null) recentCoverFailures.put(memory, android.os.SystemClock.elapsedRealtime());
                else if (bucket.revision() == revision && mediaCache.limitMb(book) > 0) covers.put(memory, result);
                for (CoverTarget item : targets) {
                    if (item.visibleOnly() && (!current(item.page()) || !item.view().isAttachedToWindow())) continue;
                    item.loaded().accept(result);
                }
            });
        });
    }
    private byte[] downloadCover(String url) throws Exception {
        var connection = (HttpURLConnection) Http.url(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        try (var stream = connection.getInputStream()) { return Http.bytes(stream, 2_500_000); }
        finally { connection.disconnect(); }
    }
    private Bitmap downloadThumbnail(String address, int edge) throws Exception {
        // A failed thumbnail must not silently trigger another download of the original image.
        return decodeCover(downloadCover(CoverThumbnail.address(address, edge)), edge);
    }
    private Bitmap shrinkCover(Bitmap bitmap, int edge) {
        if (bitmap == null) return null;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (longest <= edge) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, width * edge / longest), Math.max(1, height * edge / longest), true);
    }
    private String coverIdentity(String address) {
        if (!address.contains("/rest/getCoverArt.view?")) return address;
        String id = Uri.parse(address).getQueryParameter("id");
        return id == null ? address : sources.navCacheKey() + "\n" + id + "\n" + Uri.parse(address).getQueryParameter("size");
    }
    private Bitmap decodeCover(byte[] data, int edge) {
        if (data == null) return null;
        var dimensions = new BitmapFactory.Options();
        dimensions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, dimensions);
        int sample = 1;
        while (Math.max(dimensions.outWidth, dimensions.outHeight) / sample / 2 >= edge) sample *= 2;
        var options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }
    private String playbackCover(PlaybackService player) {
        if (player.index() < 0 || player.index() >= player.entries().size()) return player.cover;
        return coverAddress(player.entries().get(player.index()));
    }
    private String coverAddress(MediaEntry entry) {
        String cover = entry.cover();
        return "library".equals(entry.source()) ? libraryCover(cover, 256) : cover;
    }

    private String libraryCover(String key, int size) {
        if (key.trim().isEmpty()) return "";
        try { return sources.navidrome().endpoint("getCoverArt", "&size=" + size + "&id=" + Http.enc(key)); }
        catch (Exception error) { return ""; }
    }
    private EditText field(String hint, String value, int kind) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setText(value);
        edit.setInputType(kind);
        edit.setTextColor(INK);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(15);
        edit.setPadding(dp(14), dp(8), dp(14), dp(8));
        edit.setMinHeight(dp(48));
        setFieldSurface(edit, PAPER, CARD_RADIUS);
        return edit;
    }
    private void setFieldSurface(EditText field, int color, int radius) {
        var surface = new StateListDrawable();
        var focused = shape(color, radius);
        focused.setStroke(dp(1), ACCENT);
        surface.addState(new int[]{android.R.attr.state_focused}, focused);
        surface.addState(new int[]{}, shape(color, radius));
        field.setBackground(surface);
    }
    private void searchBar(String hint, String value, java.util.function.Consumer<String> submit) {
        var row = line();
        row.setBackground(glass(false, PILL_RADIUS));
        row.setPadding(dp(8), dp(3), dp(3), dp(3));
        row.addView(new UiIcon(this, UiIcon.Kind.SEARCH, 18, ACCENT), new LinearLayout.LayoutParams(dp(30), dp(40)));
        EditText query = field(hint, value, InputType.TYPE_CLASS_TEXT);
        query.setBackgroundColor(Color.TRANSPARENT);
        query.setPadding(dp(4), dp(6), dp(8), dp(6));
        query.setMinHeight(dp(44));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Runnable search = () -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
            submit.accept(query.getText().toString().trim());
        };
        query.setOnEditorActionListener((view, action, event) -> { search.run(); return true; });
        weight(row, query);
        row.addView(button("搜索", true, search));
        fill(body, row);
        gap(body, 12);
    }
    private void platform(boolean all, String current, java.util.function.Consumer<String> selected) {
        int offset = all ? 0 : 1;
        int checked = java.util.Arrays.asList(MediaSources.KEYS).indexOf(current) - offset;
        dialog().setTitle("选择来源")
            .setSingleChoiceItems(java.util.Arrays.copyOfRange(MediaSources.NAMES, offset, MediaSources.NAMES.length), Math.max(-1, checked),
                (dialog, index) -> {
                    dialog.dismiss();
                    selected.accept(MediaSources.KEYS[index + offset]);
                }).show();
    }
    private interface Work { Object run() throws Exception; }
    private void work(Work task, ScriptRuntime.Callback callback) {
        io.execute(() -> {
            try { Object result = task.run(); main.post(() -> callback.complete(result, null)); }
            catch (Exception error) {
                String message = error.getMessage();
                main.post(() -> callback.complete(null, message == null || message.trim().isEmpty() ? "操作失败" : message));
            }
        });
    }
    private void toast(String value) { if (!isFinishing()) Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private boolean current(int id) { return generation == id && !isDestroyed(); }
    private static JSONArray array(JSONObject object, String key) {
        JSONArray result = object == null ? null : object.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }
    private static List<JSONObject> objects(JSONArray items) {
        var result = new ArrayList<JSONObject>();
        for (int i = 0; i < items.length(); i++) if (items.optJSONObject(i) != null) result.add(items.optJSONObject(i));
        return result;
    }
    private void musicSearch(boolean library) {
        page("音乐", "搜索");
        var tabs = line();
        tabs.addView(button("在线搜索", !library, () -> { screen = () -> musicSearch(false); refresh(); }));
        space(tabs, 8);
        tabs.addView(button("资料库搜索", library, () -> { screen = () -> musicSearch(true); refresh(); }));
        if (!library) { space(tabs, 8); tabs.addView(button(MediaSources.label(online) + " ▾", false, () -> platform(true, online, value -> { online = value; refresh(); }))); }
        chips(body, tabs);
        gap(body, 14);
        searchBar(library ? "搜索资料库歌曲" : "输入歌名或歌手", library ? libraryQuery : musicQuery, query -> {
            if (library) libraryQuery = query; else musicQuery = query;
            refresh();
        });
        String query = library ? libraryQuery : musicQuery;
        if (query.trim().isEmpty()) {
            note(body, library ? "在已连接的音乐服务器中搜索。" : "选择聚合或单个平台。导入匹配的音源后可播放和下载。");
            return;
        }
        if (library) librarySongs(body, 0, offset -> sources.navidrome().search(query, offset));
        else onlineSongs(body, "search", MediaSources.args("source", online, "query", query), 1);
    }
    private void playlists() {
        page("音乐 / 发现", "在线歌单");
        playlistScreen = true;
        searchBar("搜索歌单", listQuery, query -> { listQuery = query; refresh(); });
        var results = column();
        var tools = line();
        tools.addView(button(MediaSources.label(listPlatform) + " ▾", false, () -> platform(true, listPlatform, value -> {
            listPlatform = value; tag = ""; tagName = "全部分类"; sort = "@hot"; sortName = "最热"; refresh();
        })));
        space(tools, 6);
        tools.addView(button(tagName + " ▾", false, this::categories));
        space(tools, 6);
        tools.addView(button(sortName + " ▾", false, this::sorting));
        chips(body, tools);
        gap(body, 8);
        var actions = line();
        actions.addView(button(grid ? "列表视图" : "封面网格", false, () -> { grid = !grid; refresh(); }));
        space(actions, 8);
        actions.addView(button("按链接打开", false, () -> prompt("打开在线歌单", "歌单链接或 ID", "", value -> {
            if (value.trim().isEmpty()) return;
            if (listPlatform.equals("all")) platform(false, "", key -> open(() -> onlineDetail("list", key, value, "歌单详情")));
            else open(() -> onlineDetail("list", listPlatform, value, "歌单详情"));
        })));
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        actions.addView(iconButton(UiIcon.Kind.REFRESH, "刷新在线歌单", false, () -> loadLists(results, 1, true)));
        fill(body, actions);
        gap(body, 10);
        fill(body, results);
        loadLists(results, 1);
    }
    private void categories() {
        if (listPlatform.equals("all")) {
            platform(false, "", source -> { listPlatform = source; tag = ""; tagName = "全部分类"; sort = "@hot"; sortName = "最热"; refresh(); categories(); });
            return;
        }
        int id = generation;
        playlists.online("tags", MediaSources.args("source", listPlatform), true, (value, error) -> {
            if (!current(id)) return;
            if (error != null) { toast(error); return; }
            JSONObject data = (JSONObject) value;
            List<JSONObject> tags = new ArrayList<>();
            tags.add(MediaSources.args("id", "", "name", "全部分类"));
            for (JSONObject item : objects(array(data, "hotTag"))) tags.add(item);
            for (JSONObject group : objects(array(data, "tags"))) {
                JSONArray nested = array(group, "list");
                if (nested.length() == 0) nested = array(group, "tags");
                if (nested.length() > 0) tags.addAll(objects(nested)); else if (group.has("id")) tags.add(group);
            }
            var used = new HashSet<String>();
            tags.removeIf(item -> !used.add(item.optString("id")));
            dialog().setTitle("歌单分类").setItems(tags.stream()
                .map(item -> MediaEntry.first(item, "name", "title")).toArray(String[]::new), (dialog, index) -> {
                JSONObject item = tags.get(index); tag = item.optString("id"); tagName = MediaEntry.first(item, "name", "title");
                listQuery = ""; refresh();
            }).show();
        });
    }
    private void sorting() {
        if (listPlatform.equals("all")) {
            dialog().setTitle("歌单排序").setItems(new String[]{"各平台默认", "最热", "最新"}, (dialog, index) -> {
                sort = new String[]{"", "@hot", "@new"}[index];
                sortName = new String[]{"默认", "最热", "最新"}[index];
                refresh();
            }).show();
            return;
        }
        int id = generation;
        playlists.online("tags", MediaSources.args("source", listPlatform), true, (value, error) -> {
            if (!current(id)) return;
            if (error != null) { toast(error); return; }
            var choices = objects(array((JSONObject) value, "sorts"));
            dialog().setTitle("歌单排序").setItems(choices.stream().map(item -> item.optString("name"))
                .toArray(String[]::new), (dialog, index) -> {
                    sort = choices.get(index).optString("id"); sortName = choices.get(index).optString("name"); refresh();
                }).show();
        });
    }
    private void loadLists(LinearLayout target, int page) {
        loadLists(target, page, false);
    }
    private JSONObject listOptions(int page) {
        return MediaSources.args("source", listPlatform, "query", listQuery, "tag", tag, "sort", sort, "page", page);
    }

    private void loadLists(LinearLayout target, int page, boolean force) {
        catalog(target, "lists", listOptions(page), force, data -> {
            if (force) target.removeAllViews();
            var lists = objects(array(data, "list"));
            if (grid) discoveryGrid(target, lists, false);
            else for (JSONObject item : lists) {
                target.addView(catalogCard(item, false, false, true), new LinearLayout.LayoutParams(-1, dp(catalogHeight(112))));
                gap(target, 8);
            }
            if (lists.isEmpty()) note(target, "没有找到匹配的歌单。");
            if (data.optBoolean("more")) more(target, () -> loadLists(target, page + 1));
        }, () -> {});
    }
    private String playlistSubtitle(JSONObject item) {
        return String.join(" · ", java.util.stream.Stream.of(
            MediaEntry.first(item, "author", "userName", "creator"), MediaEntry.first(item, "play_count", "playCount"))
            .filter(value -> !value.trim().isEmpty()).collect(java.util.stream.Collectors.toList()));
    }
    private static final int[] DISCOVERY_COLORS = {
        0xff21575c, 0xff5c3b70, 0xff8b443d, 0xff74521f, 0xff274878, 0xff3e5364, 0xff533947
    };

    private void discoveryGrid(LinearLayout target, List<JSONObject> items, boolean chart) {
        int width = getResources().getConfiguration().screenWidthDp - 32;
        int columns = width < 265 ? 1 : Math.max(2, Math.min(4, width / 175));
        int height = catalogHeight(Math.max(134, Math.min(178, ((width - (columns - 1) * 8) / columns) * 4 / 5)));
        for (int first = 0; first < items.size(); first += columns) {
            var strip = line();
            for (int col = 0; col < columns; col++) {
                if (col > 0) space(strip, 8);
                var card = first + col < items.size() ? catalogCard(items.get(first + col), chart, false, false) : new View(this);
                strip.addView(card, new LinearLayout.LayoutParams(0, dp(height), 1));
            }
            fill(target, strip);
            gap(target, 8);
        }
    }

    private int catalogHeight(int base) {
        return base + Math.round(74 * Math.max(0, getResources().getConfiguration().fontScale - 1));
    }

    private FrostedCard catalogCard(JSONObject item, boolean chart, boolean featured, boolean compact) {
        String name = MediaEntry.first(item, "name", "title");
        String source = item.optString("source", chartPlatform);
        String key = MediaEntry.first(item, chart ? "bangid" : "id", chart ? "id" : "listId", "playlistId");
        String cover = ChartArtwork.address(item);
        int variant = (source + key).hashCode();
        var card = new FrostedCard(this, DISCOVERY_COLORS[Math.floorMod(variant, DISCOVERY_COLORS.length)], variant, featured ? 22 : 17);
        card.shadeCaption(darkAppearance ? 0xe0121520 : 0xd52a1813);
        glassCards.add(card);
        action(card, chart ? () -> {
            if (activeCharts != null && pageScroll != null) activeCharts.scrollY = pageScroll.getScrollY();
            open(() -> onlineDetail("chart", source, key, name));
        } : () -> selectPlaylist(item, name));
        var labels = column();
        labels.setPadding(dp(featured ? 13 : 9), dp(featured ? 9 : 7), dp(10), dp(featured ? 11 : 8));
        var title = text(name.isBlank() ? "未命名" : name, featured ? 25 : 16, Color.WHITE, true);
        title.setShadowLayer(dp(3), 0, dp(1), 0xc7191317);
        title.setMaxLines(compact ? 1 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        fill(labels, title);
        String detail = chart ? "" : playlistSubtitle(item);
        if (!detail.isEmpty()) {
            gap(labels, 4);
            var secondary = text(detail, 11, 0xfff4edef, false);
            secondary.setShadowLayer(dp(2), 0, dp(1), 0xbe191317);
            secondary.setSingleLine();
            secondary.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, secondary);
        }
        var bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottom.setMargins(dp(7), 0, dp(7), dp(7));
        card.addView(labels, bottom);

        var provider = card.glass(13, darkAppearance ? 0xc11b242c : 0xd6fff7ee);
        var label = text(MediaSources.label(source), 11, INK, true);
        label.setPadding(dp(8), dp(5), dp(8), dp(5));
        label.setSingleLine();
        provider.addView(label, new FrameLayout.LayoutParams(-2, -2));
        var top = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        top.setMarginStart(dp(9)); top.topMargin = dp(8);
        card.addView(provider, top);

        int edge = featured ? 256 : 168;
        if (chart) {
            var target = new ChartCoverCard(card, source, key, featured ? 320 : 160, generation,
                mediaCache.bucket(false).revision(), refreshChartArtwork);
            Bitmap previous = target.force() ? null : chartArtwork.get(target.memoryKey());
            if (previous != null) card.setArtwork(previous);
            else tryChartArtwork(target, cover.isEmpty() ? List.of() : List.of(cover), 0, () -> chartDetailCache(target));
        } else if (!cover.isEmpty()) {
            loadCardCover(card, cover, edge, false);
        }
        return card;
    }

    private record ChartCoverCard(FrostedCard card, String source, String id, int edge, int page, long revision, boolean force) {
        JSONObject detailArgs() { return MediaSources.args("source", source, "id", id, "page", 1); }
        String memoryKey() { return source + ":" + id + ":" + edge + ":" + revision; }
    }

    private boolean hasOnlineNetwork() {
        return getSystemService(ConnectivityManager.class).getActiveNetwork() != null;
    }

    private boolean activeChart(ChartCoverCard target) {
        return current(target.page()) && target.card().isAttachedToWindow();
    }

    private void whenChartVisible(ChartCoverCard target, Runnable work) {
        if (!current(target.page())) return;
        Runnable visible = () -> { if (activeChart(target)) work.run(); };
        if (coverViewport != null) coverViewport.loadWhenVisible(target.card(), visible);
        else visible.run();
    }

    private void tryChartArtwork(ChartCoverCard target, List<String> urls, int index, Runnable fallback) {
        whenChartVisible(target, () -> {
            if (index >= urls.size()) { fallback.run(); return; }
            requestCover(target.card(), urls.get(index), target.edge(), false, true, bitmap -> {
                if (!activeChart(target)) return;
                if (bitmap != null) {
                    chartArtwork.put(target.memoryKey(), bitmap);
                    target.card().setArtwork(bitmap);
                }
                else tryChartArtwork(target, urls, index + 1, fallback);
            });
        });
    }

    private void chartDetailCache(ChartCoverCard target) {
        if (target.id().isEmpty()) return;
        if (target.force()) { requestChartCoverDetail(target); return; }
        playlists.cachedOnline("chart-cover", target.detailArgs(), (value, error) -> {
            if (!activeChart(target)) return;
            if (value != null) {
                tryChartArtwork(target, ChartArtwork.topTrackCover(value), 0, () -> {});
            } else cachedChartTopTrack(target);
        });
    }

    private void cachedChartTopTrack(ChartCoverCard target) {
        playlists.cachedOnline("chart", target.detailArgs(), (value, error) -> {
            if (activeChart(target)) tryChartArtwork(target, ChartArtwork.topTrackCover(value), 0, () -> requestChartCoverDetail(target));
        });
    }

    private void requestChartCoverDetail(ChartCoverCard target) {
        if (!hasOnlineNetwork()) return;
        pendingChartCovers.addLast(target);
        pumpChartCoverDetails();
    }

    private void pumpChartCoverDetails() {
        while (chartCoverRequests < 2 && !pendingChartCovers.isEmpty()) {
            ChartCoverCard target = pendingChartCovers.removeFirst();
            if (!activeChart(target) || !hasOnlineNetwork()) continue;
            if (!target.card().getGlobalVisibleRect(visibleCard)) {
                whenChartVisible(target, () -> requestChartCoverDetail(target));
                continue;
            }
            String key = target.source() + ":" + target.id() + ":" + mediaCache.bucket(false).revision();
            long now = android.os.SystemClock.elapsedRealtime();
            Long last = lastChartCoverFetch.get(key);
            if (!target.force() && last != null && now - last < 120_000) continue;
            lastChartCoverFetch.put(key, now);
            chartCoverRequests++;
            playlists.online("chart-cover", target.detailArgs(), !target.force(), (value, error) -> {
                chartCoverRequests--;
                if (activeChart(target)) tryChartArtwork(target, ChartArtwork.topTrackCover(value), 0, () -> {});
                pumpChartCoverDetails();
            });
        }
    }

    private void loadCardCover(FrostedCard card, String address, int size, boolean book) {
        if (address.isBlank()) return;
        Runnable load = () -> requestCover(card, address, CoverThumbnail.edge(dp(size)), book, true, bitmap -> {
            if (bitmap != null) card.setArtwork(bitmap);
        });
        if (coverViewport != null) coverViewport.loadWhenVisible(card, load);
        else load.run();
    }

    private FrameLayout discoveryCard(String name, String subtitle, String image, int color, boolean featured, View motif, Runnable action) {
        var card = new FrameLayout(this);
        clickable(card, color, CARD_RADIUS, action);
        card.setClipToOutline(true);
        if (motif instanceof UiIcon) {
            var artwork = new FrameLayout.LayoutParams(dp(featured ? 132 : 77), -1, Gravity.END);
            artwork.setMarginEnd(dp(featured ? 10 : 4));
            card.addView(motif, artwork);
        } else card.addView(motif, new FrameLayout.LayoutParams(-1, -1));
        var overlay = new View(this);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{color, color, (color & 0x00ffffff) | 0x22000000}));
        card.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        var shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.TRANSPARENT, 0x00000000, 0x870e1117}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        var labels = column();
        labels.setPadding(dp(featured ? 20 : 12), dp(6), dp(10), dp(featured ? 19 : 12));
        TextView title = text(name, featured ? 28 : 16, Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        fill(labels, title);
        if (!subtitle.isEmpty()) {
            gap(labels, 5);
            TextView caption = text(subtitle, featured ? 14 : 12, 0xffeff1f5, false);
            caption.setMaxLines(1);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, caption);
        }
        card.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setForeground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff), null, shape(PAPER, CARD_RADIUS)));
        if (!image.isEmpty()) addDiscoveryCover(card, image, featured);
        return card;
    }

    private void addDiscoveryCover(FrameLayout card, String image, boolean featured) {
        var cover = catalogArt(image, featured ? 205 : 128);
        cover.setBackground(null);
        cover.setClipToOutline(false);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(cover, 1, new FrameLayout.LayoutParams(dp(featured ? 205 : 128), -1, Gravity.END));
    }
    private void selectPlaylist(JSONObject item, String name) {
        String key = MediaEntry.first(item, "id", "listId", "playlistId");
        String source = item.optString("source");
        playlists.remember(source, key, name, null, ChartArtwork.address(item));
        open(() -> onlineDetail("list", source, key, name));
    }
    private void following() {
        page("音乐", "关注的歌单");
        List<JSONObject> list = playlists.following();
        if (list.isEmpty()) note(body, "打开在线歌单后，点「关注歌单」即可添加到这里。");
        for (JSONObject item : list) {
            String title = item.optString("title"), source = item.optString("source"), id = item.optString("id");
            row(body, title, MediaSources.label(source), ChartArtwork.address(item), UiIcon.Kind.LIST, true,
                () -> open(() -> onlineDetail("list", source, id, title)));
        }
    }
    private static final class ChartPageState {
        final long revision;
        JSONObject catalog;
        int scrollY;
        ChartPageState(long revision) { this.revision = revision; }
    }

    private void charts() {
        String cacheKey = chartPlatform + "\n" + chartQuery;
        long revision = mediaCache.bucket(false).revision();
        ChartPageState saved = chartPages.get(cacheKey);
        if (saved == null || saved.revision != revision) {
            saved = new ChartPageState(revision);
            chartPages.put(cacheKey, saved);
        }
        ChartPageState state = saved;
        page("音乐 / 发现", "在线排行榜");
        activeCharts = state;
        playlistScreen = true;
        searchBar("搜索榜单名称", chartQuery, query -> { chartQuery = query; refresh(); });
        var results = column();
        var controls = line();
        controls.addView(button(MediaSources.label(chartPlatform) + " ▾", false, () -> platform(false, chartPlatform, value -> {
            chartPlatform = value; refresh();
        })));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(iconButton(UiIcon.Kind.REFRESH, "刷新在线榜单", false, () -> loadCharts(results, true, state)));
        fill(body, controls);
        gap(body, 10);
        fill(body, results);
        loadCharts(results, false, state);
    }
    private void loadCharts(LinearLayout target, boolean force, ChartPageState state) {
        CatalogResult render = data -> {
            target.removeAllViews();
            state.catalog = data;
            refreshChartArtwork = force;
            if (force) chartArtwork.evictAll();
            var boards = new ArrayList<JSONObject>();
            for (JSONObject item : objects(array(data, "list"))) {
                if (matches(MediaEntry.first(item, "name", "title"), chartQuery)) boards.add(item);
            }
            if (boards.isEmpty()) { refreshChartArtwork = false; note(target, "没有找到匹配的榜单。"); return; }
            if (chartQuery.isEmpty()) {
                target.addView(catalogCard(boards.remove(0), true, true, false), new LinearLayout.LayoutParams(-1, dp(catalogHeight(183))));
                gap(target, 11);
            }
            discoveryGrid(target, boards, true);
            refreshChartArtwork = false;
            ScrollView scroll = pageScroll;
            if (scroll != null && state.scrollY > 0) {
                scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (activeCharts == state && scroll == pageScroll) scroll.scrollTo(0, state.scrollY);
                    }
                });
            }
        };
        // Returning from a chart restores the existing snapshot without catalog or cover refreshes.
        if (!force && state.catalog != null) render.show(state.catalog);
        else catalog(target, "charts", MediaSources.args("source", chartPlatform), force, render, () -> {});
    }
    private boolean matches(String input, String keyword) {
        return input.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
    private interface CatalogResult { void show(JSONObject result); }
    private void catalog(LinearLayout target, String operation, JSONObject options, CatalogResult callback) {
        catalog(target, operation, options, callback, () -> {});
    }
    private void catalog(LinearLayout target, String operation, JSONObject options, CatalogResult callback, Runnable failed) {
        catalog(target, operation, options, false, callback, failed);
    }
    private void catalog(LinearLayout target, String operation, JSONObject options, boolean force, CatalogResult callback, Runnable failed) {
        if (force && target.getTag() != null) return;
        int id = generation;
        TextView pending = text("加载中…", 14, MUTED, false);
        pending.setPadding(0, dp(14), 0, dp(14));
        if (force) {
            target.setTag(pending);
            target.addView(pending, 0);
        } else fill(target, pending);
        ScriptRuntime.Callback completed = (value, error) -> {
            if (error == null && value instanceof JSONObject data && (force || current(id))) {
                updateDiscoveryCovers(operation, options, data, force);
            }
            if (!current(id) || pending.getParent() != target) return;
            if (force) target.setTag(null);
            target.removeView(pending);
            if (error != null) { note(target, error); failed.run(); return; }
            JSONObject data = (JSONObject) value;
            JSONArray unavailable = array(data, "failed");
            if (unavailable.length() > 0) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < unavailable.length(); i++) names.add(MediaSources.label(unavailable.optString(i)));
                note(target, "暂不可用：" + String.join("、", names));
            }
            callback.show(data);
        };
        if (List.of("lists", "list", "charts", "chart").contains(operation)) playlists.online(operation, options, !force, completed);
        else sources.catalog(operation, options, completed);
    }

    private void updateDiscoveryCovers(String operation, JSONObject options, JSONObject data, boolean rotate) {
        if ("lists".equals(operation) && options.optInt("page", 1) == 1) {
            playlists.rememberDiscoveryCovers(PlaylistRefresh.DISCOVERY_LISTS, ChartArtwork.catalog(data), rotate);
        } else if ("charts".equals(operation)
            && (rotate || !chartEntryLoading && playlists.discoveryCovers(PlaylistRefresh.DISCOVERY_CHARTS).isEmpty())) {
            chartEntryLoading = true;
            resolveChartEntryCover(options.optString("source"), data, rotate, ++chartEntryRequest);
        } else if ("chart".equals(operation) && options.optInt("page", 1) == 1) {
            var covers = ChartArtwork.topTrackCover(data);
            if (rotate && !covers.isEmpty()) finishChartEntryCover(covers, true, ++chartEntryRequest);
            else playlists.rememberDiscoveryCovers(PlaylistRefresh.DISCOVERY_CHARTS, covers, false);
        }
    }
    private void more(LinearLayout target, Runnable action) {
        TextView[] self = new TextView[1];
        self[0] = button("加载下一页", false, () -> { target.removeView(self[0]); action.run(); });
        target.addView(self[0]);
        gap(target, 10);
    }
    private void onlineSongs(LinearLayout target, String operation, JSONObject options, int page) {
        try { options.put("page", page); } catch (Exception ignored) {}
        catalog(target, operation, options, data -> {
            var entries = objects(array(data, "list")).stream().map(MediaEntry::online).collect(java.util.stream.Collectors.toList());
            songs(target, entries, false, "", "");
            if (data.optBoolean("more")) more(target, () -> onlineSongs(target, operation, options, page + 1));
        });
    }
    private void onlineDetail(String operation, String source, String id, String title) {
        page(MediaSources.label(source), title);
        if ("list".equals(operation)) {
            playlistScreen = true;
            playlists.remember(source, id, title, null);
            TextView[] follow = new TextView[1];
            follow[0] = button(playlists.followed(source, id) ? "取消关注" : "关注歌单", false, () -> {
                boolean enabled = !playlists.followed(source, id);
                playlists.remember(source, id, title, enabled);
                follow[0].setText(enabled ? "取消关注" : "关注歌单");
            });
            body.addView(follow[0]);
            gap(body, 10);
        }
        var entries = new ArrayList<MediaEntry>();
        var keys = new HashSet<String>();
        var filter = new String[]{""};
        final int[] next = {1};
        final boolean[] hasMore = {false};
        final boolean[] loading = {false};
        final boolean[] force = {false};
        final boolean[] playAll = {false};
        var results = column();
        Runnable[] request = new Runnable[1];
        Runnable render = () -> {
            results.removeAllViews();
            var selected = entries.stream().filter(entry -> matches(entry.title() + " " + entry.subtitle(), filter[0])).collect(java.util.stream.Collectors.toList());
            if (selected.isEmpty()) note(results, "没有找到匹配的音频。");
            else songs(results, selected, false, "", operation.equals("chart") && filter[0].trim().isEmpty() ? "rank" : "",
                0, selected.size());
            if (hasMore[0]) more(results, request[0]);
        };
        request[0] = () -> {
            if (loading[0]) return;
            loading[0] = true;
            boolean reload = force[0];
            force[0] = false;
            catalog(results, operation, MediaSources.args("source", source, "id", id, "page", reload ? 1 : next[0]), reload, data -> {
                loading[0] = false;
                if (reload) { entries.clear(); keys.clear(); next[0] = 1; }
                if (next[0] == 1 && "list".equals(operation)) {
                    String cover = ChartArtwork.address(data);
                    if (cover.isEmpty()) cover = ChartArtwork.address(data.optJSONObject("playlist"));
                    if (!cover.isEmpty()) playlists.remember(source, id, title, null, cover);
                }
                int before = entries.size();
                for (JSONObject item : objects(array(data, "list"))) {
                    MediaEntry entry = MediaEntry.online(item);
                    if (keys.add(entry.source() + ":" + entry.id())) entries.add(entry);
                }
                hasMore[0] = data.optBoolean("more") && entries.size() > before;
                next[0]++;
                render.run();
                if (playAll[0] && hasMore[0] && next[0] <= 100) request[0].run();
                else if (playAll[0]) {
                    playAll[0] = false;
                    if (hasMore[0]) toast("榜单分页超过上限，请按页播放");
                    else if (!entries.isEmpty()) PlaybackService.queue(this, List.copyOf(entries), 0, false);
                }
            }, () -> {
                loading[0] = false;
                if (playAll[0]) { playAll[0] = false; toast("未能获取完整列表"); }
            });
        };
        searchBar("在当前列表中查找歌曲", "", query -> { filter[0] = query; if (!loading[0]) render.run(); });
        var detailControls = line();
        weight(detailControls, button("播放全部", true, () -> {
            if (loading[0] || hasMore[0]) {
                playAll[0] = true;
                if (!loading[0]) request[0].run();
            } else if (entries.isEmpty()) toast("列表里还没有可播放的歌曲");
            else PlaybackService.queue(this, List.copyOf(entries), 0, false);
        }));
        space(detailControls, 9);
        detailControls.addView(iconButton(UiIcon.Kind.REFRESH, "刷新列表", false, () -> {
            if (!loading[0]) { force[0] = true; request[0].run(); }
        }));
        fill(body, detailControls);
        gap(body, 10);
        fill(body, results);
        request[0].run();
    }
    private void songs(LinearLayout target, List<MediaEntry> entries, boolean audiobook, String collection, String mode) {
        if (entries.isEmpty()) { note(target, "没有找到匹配的音频。"); return; }
        songs(target, entries, audiobook, collection, mode, 0, entries.size());
    }
    private void songs(LinearLayout target, List<MediaEntry> entries, boolean audiobook, String collection, String mode, int from, int to) {
        songs(target, entries, audiobook, collection, mode, from, to, false);
    }
    private void songs(LinearLayout target, List<MediaEntry> entries, boolean audiobook, String collection, String mode, int from, int to, boolean catalog) {
        songs(target, entries, audiobook, collection, mode, from, to, catalog, null);
    }
    private void songs(LinearLayout target, List<MediaEntry> entries, boolean audiobook, String collection, String mode, int from, int to,
                       boolean catalog, java.util.function.Consumer<MediaEntry> removeFromPlaylist) {
        for (int i = from; i < to; i++) {
            final int index = i;
            MediaEntry entry = entries.get(i);
            boolean library = entry.source().equals("library");
            var strip = line();
            FrostedCard frosted = catalog ? new FrostedCard(this, PAPER, entry.id().hashCode(), 15) : null;
            if (frosted != null) {
                frosted.setArtwork(homeBitmap(darkAppearance ? R.drawable.home_music_dark : R.drawable.home_music_light));
                glassCards.add(frosted);
                action(frosted, () -> PlaybackService.queue(this, entries, index, audiobook));
            }
            strip.setPadding(dp(10), dp(11), dp(10), dp(11));
            if ("rank".equals(mode)) {
                boolean featuredRank = i < 3;
                TextView rank = badge(String.valueOf(i + 1), featuredRank ? ACCENT : MUTED, featuredRank ? ACCENT_TINT : SOFT);
                rank.setContentDescription("第 " + (i + 1) + " 名");
                rank.setPadding(dp(3), dp(5), dp(3), dp(5));
                View number = frosted == null ? rank : glassBadge(frosted, rank, featuredRank);
                strip.addView(number, new LinearLayout.LayoutParams(dp(30), -2));
                space(strip, 6);
            }
            String cover = coverAddress(entry);
            if (!cover.trim().isEmpty()) {
                ImageView artwork;
                if (frosted != null) artwork = catalogArt(cover, 44, frosted::setArtwork);
                else if (!library && !audiobook) artwork = catalogArt(cover, 44);
                else artwork = art(cover, 44);
                strip.addView(artwork, new LinearLayout.LayoutParams(dp(44), dp(44)));
                space(strip, 10);
            }
            var labels = column();
            var title = text(entry.title(), 15, INK, true);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, title);
            gap(labels, 5);
            String album = MediaEntry.first(entry.raw(), "album", "albumName");
            String subtitle = entry.subtitle();
            if (library && !album.isEmpty()) subtitle += " · " + album;
            else if (!library && !audiobook && !entry.source().equals("offline")) subtitle += " · " + MediaSources.label(entry.source());
            String format = entry.raw().optString("suffix");
            int bitrate = entry.raw().optInt("bitRate");
            String quality = library ? (format + (bitrate > 0 ? " " + bitrate + "K" : "")).trim() : bestQuality(entry.raw());
            int seconds = TrackSort.seconds(entry);
            String duration = library && seconds > 0 ? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60) : entry.raw().optString("interval");
            if (!duration.trim().isEmpty()) subtitle += " · " + duration;
            TextView detail = text(subtitle, 12, MUTED, false);
            detail.setMaxLines(2);
            fill(labels, detail);
            if (!quality.trim().isEmpty()) {
                var qualityBadge = badge(quality, ACCENT, ACCENT_TINT);
                var placement = new LinearLayout.LayoutParams(-2, -2);
                placement.topMargin = dp(4);
                labels.addView(frosted == null ? qualityBadge : glassBadge(frosted, qualityBadge, true), placement);
            }
            labels.setPadding(0, dp(3), 0, dp(3));
            labels.setOnClickListener(view -> PlaybackService.queue(this, entries, index, audiobook));
            weight(strip, labels);
            space(strip, 6);
            if (library) {
                boolean saved = serverFavorite(entry);
                var heart = iconButton(saved ? UiIcon.Kind.HEART_FILLED : UiIcon.Kind.HEART,
                    saved ? "取消服务器收藏" : "添加服务器收藏", false, 48, 19, false, () -> {});
                heart.setOnClickListener(view -> toggleServerFavorite(entry, heart));
                strip.addView(heart);
            } else strip.addView(iconButton(UiIcon.Kind.PLAY, "播放 " + entry.title(), false, 48, 19, false,
                () -> PlaybackService.queue(this, entries, index, audiobook)));
            if (!entry.source().equals("offline")) {
                space(strip, 5);
                var actions = iconButton(UiIcon.Kind.ELLIPSIS, entry.title() + "的更多操作", false, 48, 19, false,
                    () -> songActions(entry, audiobook, collection, index + 1,
                        removeFromPlaylist == null ? null : () -> removeFromPlaylist.accept(entry)));
                strip.addView(actions);
            }
            strip.setBackground(shape(PAPER, CARD_RADIUS));
            if (frosted == null) fill(target, strip);
            else {
                frosted.addView(strip, new FrameLayout.LayoutParams(-1, -2));
                fill(target, frosted);
            }
            gap(target, 6);
        }
    }

    private FrameLayout glassBadge(FrostedCard card, TextView label, boolean accent) {
        int tint = darkAppearance ? (accent ? 0xe12b4744 : 0xe1313440) : (accent ? 0xeef4ded2 : 0xf2eee7dd);
        var lens = card.glass(9, tint);
        label.setBackground(null);
        lens.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return lens;
    }

    private final Map<String, Boolean> serverFavorites = new java.util.HashMap<>();
    private final HashSet<String> updatingFavorites = new HashSet<>();

    private boolean serverFavorite(MediaEntry entry) {
        return serverFavorites.getOrDefault(likeKey(entry), !entry.raw().optString("starred", "").isEmpty());
    }

    private void toggleServerFavorite(MediaEntry entry, FrameLayout heart) {
        String key = likeKey(entry);
        if (!updatingFavorites.add(key)) return;
        boolean add = !serverFavorite(entry);
        Navidrome server = sources.navidrome();
        work(() -> { server.favorite(entry.id(), add); return null; }, (value, error) -> {
            updatingFavorites.remove(key);
            if (error != null) { toast(error); return; }
            serverFavorites.put(key, add);
            iconState(heart, add ? UiIcon.Kind.HEART_FILLED : UiIcon.Kind.HEART, ACCENT,
                add ? "取消服务器收藏" : "添加服务器收藏");
            toast(add ? "已加入服务器收藏" : "已取消服务器收藏");
        });
    }
    private void songActions(MediaEntry entry, boolean audiobook, String collection, int track, Runnable removeFromPlaylist) {
        boolean navidrome = entry.source().equals("library");
        boolean library = !audiobook && entry.source().equals("library") && !entry.id().isEmpty();
        String next = audiobook ? "下一集播放" : "下一首播放";
        var actions = new LinkedHashMap<String, Runnable>();
        actions.put(next, () -> {
            PlaybackService player = PlaybackService.active;
            if (player == null) PlaybackService.queue(this, List.of(entry), 0, audiobook);
            else if (player.playNext(entry, audiobook)) toast(audiobook ? "已加入下一集播放" : "已加入下一首播放");
            else toast("当前队列正在播放另一种内容");
        });
        if (!audiobook) actions.put("下载", () -> downloadChoice(List.of(entry), false, collection, track));
        if (!audiobook && !navidrome) actions.put("推送到服务器下载", () -> {
            if (!serverDownloadEnabled()) open(this::settings);
            else chooseDownloadQuality(List.of(entry), false, collection, track, true);
        });
        if (library) {
            actions.put("查看", () -> showLibrarySongView(entry));
            actions.put("加入歌单", () -> chooseLibraryPlaylist(entry));
            if (removeFromPlaylist != null) actions.put("移出歌单", removeFromPlaylist);
        }
        var callbacks = new ArrayList<>(actions.values());
        dialog().setTitle(entry.title()).setItems(actions.keySet().toArray(new String[0]),
            (picker, index) -> callbacks.get(index).run()).show();
    }

    private void showLibrarySongView(MediaEntry song) {
        String account = sources.navCacheKey();
        var picker = new android.app.Dialog(this);
        var sheet = column();
        sheet.setPadding(dp(18), dp(12), dp(18), dp(18));
        var grip = new View(this);
        grip.setBackground(shape(MUTED, 4));
        var gripSize = new LinearLayout.LayoutParams(dp(32), dp(4));
        gripSize.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(grip, gripSize);
        gap(sheet, 14);
        var heading = line();
        weight(heading, text("查看", 24, INK, true));
        heading.addView(iconButton(UiIcon.Kind.CLOSE, "关闭查看菜单", false, 44, 20, false, picker::dismiss));
        fill(sheet, heading);
        var title = text(song.title(), 13, MUTED, false);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        fill(sheet, title);
        gap(sheet, 12);
        var scroll = new ScrollView(this);
        var options = column();
        scroll.addView(options);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        java.util.function.Consumer<Runnable> choose = action -> {
            picker.dismiss();
            if (!account.equals(sources.navCacheKey())) { toast("音乐库账号已变更，请重新打开歌曲"); return; }
            action.run();
        };
        row(options, "专辑", MediaEntry.first(song.raw(), "album"), "", UiIcon.Kind.ALBUM,
            () -> choose.accept(() -> viewLibrarySongAlbum(song)));
        row(options, "艺术家", LibrarySongDetails.artistName(song.raw(), false), "", UiIcon.Kind.ARTIST,
            () -> choose.accept(() -> viewLibrarySongArtists(song, false)));
        row(options, "歌曲详情", "音质、文件信息、播放记录与回放增益", "", UiIcon.Kind.INFO,
            () -> choose.accept(() -> open(() -> librarySongDetails(song))));
        picker.setContentView(sheet);
        showBottomSheet(picker, sheet, .57f);
    }

    private void viewLibrarySongAlbum(MediaEntry song) {
        String albumId = MediaEntry.first(song.raw(), "albumId");
        if (!albumId.isEmpty()) {
            String title = MediaEntry.first(song.raw(), "album");
            openLibraryDiscoveryAlbum(MediaSources.args("id", albumId, "name", title.isEmpty() ? "专辑" : title));
            return;
        }
        int pageId = generation;
        String account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        toast("正在读取关联专辑…");
        work(() -> LibrarySongDetails.merge(song, server.songDetails(song.id())), (value, error) -> {
            if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
            if (error != null) { toast(error); return; }
            var details = (MediaEntry) value;
            if (MediaEntry.first(details.raw(), "albumId").isEmpty()) { toast("服务器未提供关联专辑"); return; }
            viewLibrarySongAlbum(details);
        });
    }

    private void viewLibrarySongArtists(MediaEntry song, boolean albumArtists) {
        var known = LibrarySongDetails.artists(song.raw(), albumArtists);
        if (!known.isEmpty()) { chooseLibrarySongArtist(known); return; }
        int pageId = generation;
        String account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        toast("正在读取关联艺术家…");
        work(() -> {
            MediaEntry details = LibrarySongDetails.merge(song, server.songDetails(song.id()));
            var artists = LibrarySongDetails.artists(details.raw(), albumArtists);
            String albumId = MediaEntry.first(details.raw(), "albumId");
            if (artists.isEmpty() && albumArtists && !albumId.isEmpty()) {
                artists = LibrarySongDetails.artists(server.albumDetails(albumId), false);
            }
            return artists;
        }, (value, error) -> {
            if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
            if (error != null) { toast(error); return; }
            @SuppressWarnings("unchecked") var artists = (List<LibrarySongDetails.Artist>) value;
            if (artists.isEmpty()) toast("服务器未提供关联艺术家");
            else chooseLibrarySongArtist(artists);
        });
    }

    private void chooseLibrarySongArtist(List<LibrarySongDetails.Artist> artists) {
        if (artists.size() == 1) { openLibrarySongArtist(artists.get(0)); return; }
        String account = sources.navCacheKey();
        dialog().setTitle("选择艺术家").setItems(artists.stream().map(LibrarySongDetails.Artist::name).toArray(String[]::new),
            (picker, index) -> {
                if (account.equals(sources.navCacheKey())) openLibrarySongArtist(artists.get(index));
            }).show();
    }

    private void openLibrarySongArtist(LibrarySongDetails.Artist artist) {
        open(() -> {
            page("音乐库 / 艺术家", artist.name());
            int pageId = generation;
            String account = sources.navCacheKey();
            Navidrome server = sources.navidrome();
            var results = column();
            fill(body, results);
            note(results, "正在读取专辑…");
            work(() -> server.artist(artist.id()), (value, error) -> {
                if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
                results.removeAllViews();
                if (error != null) { note(results, error); return; }
                var albums = objects((JSONArray) value);
                if (albums.isEmpty()) note(results, "这位艺术家暂无专辑。");
                for (JSONObject album : albums) libraryAlbumRow(results, album);
            });
        });
    }

    private void librarySongDetails(MediaEntry song) {
        page("音乐库", "歌曲详情");
        int pageId = generation;
        String account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        var feedback = line();
        var status = text("正在更新歌曲信息…", 12, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        weight(feedback, status);
        var reload = iconButton(UiIcon.Kind.REFRESH, "刷新歌曲信息", false, 44, 20, false, () -> {});
        feedback.addView(reload);
        fill(body, feedback);
        var contents = column();
        fill(body, contents);
        renderLibrarySongDetails(contents, song);
        boolean[] loading = {false};
        Runnable request = () -> {
            if (loading[0]) return;
            loading[0] = true;
            reload.setEnabled(false);
            reload.setAlpha(.35f);
            status.setText("正在更新歌曲信息…");
            work(() -> LibrarySongDetails.merge(song, server.songDetails(song.id())), (value, error) -> {
                if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
                loading[0] = false;
                reload.setEnabled(true);
                reload.setAlpha(1f);
                if (error != null) { status.setText("未能更新信息，当前显示列表中已有的数据 · " + error); return; }
                var scroll = pageScroll;
                int scrollY = scroll == null ? 0 : scroll.getScrollY();
                contents.removeAllViews();
                renderLibrarySongDetails(contents, (MediaEntry) value);
                status.setText("歌曲信息来自当前音乐库");
                if (scroll != null) scroll.post(() -> { if (current(pageId)) scroll.scrollTo(0, scrollY); });
            });
        };
        reload.setOnClickListener(view -> request.run());
        request.run();
    }

    private void renderLibrarySongDetails(LinearLayout target, MediaEntry song) {
        JSONObject raw = song.raw();
        var summary = line();
        summary.setPadding(0, dp(8), 0, dp(10));
        summary.addView(discoveryArtwork(raw, 64), new LinearLayout.LayoutParams(dp(64), dp(64)));
        space(summary, 13);
        var labels = column();
        fill(labels, text(song.title(), 18, INK, true));
        gap(labels, 6);
        fill(labels, text(LibrarySongDetails.artistName(raw, false), 13, MUTED, false));
        weight(summary, labels);
        boolean saved = serverFavorite(song);
        var heart = iconButton(saved ? UiIcon.Kind.HEART_FILLED : UiIcon.Kind.HEART,
            saved ? "取消服务器收藏" : "添加服务器收藏", false, 44, 21, false, () -> {});
        heart.setOnClickListener(view -> toggleServerFavorite(song, heart));
        summary.addView(heart);
        fill(target, summary);

        var basic = songInfoGroup(target, "基础信息");
        songInfoRow(basic, "标题", LibrarySongDetails.text(raw, "title", "name"), null);
        boolean hasAlbum = !MediaEntry.first(raw, "albumId").isEmpty();
        songInfoRow(basic, "专辑", LibrarySongDetails.text(raw, "album"), hasAlbum ? () -> viewLibrarySongAlbum(song) : null);
        String albumArtist = LibrarySongDetails.artistName(raw, true);
        boolean hasAlbumArtist = !LibrarySongDetails.artists(raw, true).isEmpty()
            || (hasAlbum && !LibrarySongDetails.UNKNOWN.equals(albumArtist));
        songInfoRow(basic, "专辑艺术家", albumArtist, hasAlbumArtist ? () -> viewLibrarySongArtists(song, true) : null);
        songInfoRow(basic, "艺术家", LibrarySongDetails.artistName(raw, false),
            LibrarySongDetails.artists(raw, false).isEmpty() ? null : () -> viewLibrarySongArtists(song, false));
        songInfoRow(basic, "歌词", "查看歌词", () -> open(() -> librarySongLyrics(song)));
        songInfoRow(basic, "年份", LibrarySongDetails.number(raw, "year", "", false), null);
        songInfoRow(basic, "流派", LibrarySongDetails.text(raw, "genre"), null);
        songInfoRow(basic, "碟号", LibrarySongDetails.number(raw, "discNumber", "", false), null);
        songInfoRow(basic, "音轨号", LibrarySongDetails.number(raw, "track", "", false), null);

        var extended = songInfoGroup(target, "扩展信息");
        songInfoRow(extended, "文件格式", LibrarySongDetails.text(raw, "suffix", "contentType"), null);
        songInfoRow(extended, "时长", LibrarySongDetails.duration(raw), null);
        songInfoRow(extended, "码率", LibrarySongDetails.number(raw, "bitRate", " kbps", false), null);
        songInfoRow(extended, "采样率", LibrarySongDetails.number(raw, raw.has("samplingRate") ? "samplingRate" : "sampleRate", " Hz", false), null);
        songInfoRow(extended, "位深", LibrarySongDetails.number(raw, "bitDepth", " bit", false), null);
        songInfoRow(extended, "声道", LibrarySongDetails.number(raw, "channelCount", "", false), null);
        songInfoRow(extended, "播放次数", LibrarySongDetails.number(raw, "playCount", "", true), null);
        songInfoRow(extended, "最近播放时间", LibrarySongDetails.date(raw, "played", "lastPlayed"), null);
        songInfoRow(extended, "创建时间", LibrarySongDetails.date(raw, "created"), null);
        songInfoRow(extended, "文件路径", LibrarySongDetails.text(raw, "path"), null);
        songInfoRow(extended, "文件大小", LibrarySongDetails.fileSize(raw), null);

        var replay = songInfoGroup(target, "回放增益");
        songInfoRow(replay, "专辑增益", LibrarySongDetails.gain(raw, "albumGain", true), null);
        songInfoRow(replay, "专辑峰值", LibrarySongDetails.gain(raw, "albumPeak", false), null);
        songInfoRow(replay, "音轨增益", LibrarySongDetails.gain(raw, "trackGain", true), null);
        songInfoRow(replay, "音轨峰值", LibrarySongDetails.gain(raw, "trackPeak", false), null);
    }

    private LinearLayout songInfoGroup(LinearLayout target, String title) {
        gap(target, 20);
        var heading = text(title, 13, ACCENT, true);
        heading.setAccessibilityHeading(true);
        fill(target, heading);
        gap(target, 9);
        var group = column();
        group.setBackground(shape(PAPER, 18));
        group.setClipToOutline(true);
        fill(target, group);
        return group;
    }

    private void songInfoRow(LinearLayout group, String title, String value, Runnable select) {
        if (group.getChildCount() > 0) {
            var divider = new View(this);
            divider.setBackgroundColor(SOFT);
            var size = new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f)));
            size.setMargins(dp(16), 0, dp(16), 0);
            group.addView(divider, size);
        }
        var row = line();
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        var label = text(title, 14, INK, false);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, .36f));
        space(row, 10);
        var content = text(value, 14, select == null ? MUTED : ACCENT, false);
        content.setGravity(Gravity.END);
        content.setTextIsSelectable(select == null);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, .64f));
        if (select != null) {
            space(row, 7);
            row.addView(new UiIcon(this, UiIcon.Kind.NEXT, 17, MUTED), new LinearLayout.LayoutParams(dp(18), dp(24)));
            clickable(row, Color.TRANSPARENT, 12, select);
            row.setContentDescription(title + "，" + value);
        }
        fill(group, row);
    }

    private void librarySongLyrics(MediaEntry song) {
        page("音乐库 / 歌曲详情", "歌词");
        fill(body, text(song.title(), 19, INK, true));
        gap(body, 18);
        var content = text("正在加载歌词…", 16, MUTED, false);
        content.setLineSpacing(dp(8), 1);
        content.setTextIsSelectable(true);
        fill(body, content);
        int pageId = generation;
        String account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        work(() -> server.lyrics(song), (value, error) -> {
            if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
            if (error != null) { content.setText(error); return; }
            String lyrics = Lyrics.parse((String) value, "").stream().map(Lyrics.Line::text).collect(Collectors.joining("\n"));
            content.setText(lyrics.isBlank() ? "服务器暂无这首歌曲的歌词。" : lyrics);
        });
    }

    private String likeKey(MediaEntry entry) {
        return entry.source() + "\n" + entry.id() + (entry.source().equals("library") ? "\n" + MediaCache.digest(sources.navCacheKey()) : "");
    }
    private boolean liked(MediaEntry entry) {
        String key = likeKey(entry);
        return objects(sources.registered("liked-tracks")).stream().anyMatch(row -> key.equals(row.optString("key")));
    }
    private boolean toggleLike(MediaEntry entry) {
        String key = likeKey(entry);
        var saved = new JSONArray();
        boolean add = !liked(entry);
        if (add) saved.put(MediaSources.args("key", key, "id", entry.id(), "source", entry.source(), "title", entry.title(),
            "subtitle", entry.subtitle(), "cover", entry.cover(), "raw", entry.raw()));
        for (JSONObject row : objects(sources.registered("liked-tracks"))) if (!key.equals(row.optString("key"))) saved.put(row);
        prefs.edit().putString("liked-tracks", saved.toString()).apply();
        return add;
    }
    private void likedSongs() {
        page("音乐", "我喜欢的音乐");
        var entries = likedEntries();
        if (entries.isEmpty()) note(body, "在播放页点爱心即可收藏歌曲。");
        else songs(body, entries, false, "", "");
    }
    private List<MediaEntry> likedEntries() {
        var entries = new ArrayList<MediaEntry>();
        for (JSONObject row : objects(sources.registered("liked-tracks"))) {
            JSONObject raw = row.optJSONObject("raw");
            if (raw == null) continue;
            var entry = new MediaEntry(row.optString("id"), row.optString("title"), row.optString("subtitle"), row.optString("cover"), row.optString("source"), raw);
            if (likeKey(entry).equals(row.optString("key"))) entries.add(entry);
        }
        return entries;
    }
    private String bestQuality(JSONObject item) {
        JSONObject types = item.optJSONObject("_types");
        if (types == null) return "";
        if (types.has("flac24bit")) return "Hi-Res";
        if (types.has("flac")) return "无损";
        if (types.has("320k")) return "320k";
        return "";
    }
    private void prompt(String title, String hint, String value, java.util.function.Consumer<String> action) {
        var panel = column();
        panel.setPadding(dp(20), dp(10), dp(20), 0);
        EditText input = field(hint, value, InputType.TYPE_CLASS_TEXT);
        fill(panel, input);
        dialog().setTitle(title).setView(panel).setNegativeButton("取消", null)
            .setPositiveButton("确定", (dialog, index) -> action.accept(input.getText().toString().trim())).show();
    }
    private static String time(int ms) {
        return String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1000 % 60);
    }

    private void settings() {
        page("音浪", "总设置");
        section("外观");
        row(body, "个性化", "首页模板 · 主题 · 播放条歌词 · 发现页", "", () -> open(this::personalization));
        row(body, "在线播放音质", "Navidrome · 移动网络与 Wi‑Fi", "", () -> open(this::libraryAudioQuality));
        if (appearance() == Appearance.AUTO) {
            row(body, "切换到浅色", clockLabel(appearanceStart(false)), "", UiIcon.Kind.TIMER, () -> chooseAppearanceTime(false));
            row(body, "切换到深色", clockLabel(appearanceStart(true)), "", UiIcon.Kind.TIMER, () -> chooseAppearanceTime(true));
        }
        section("存储");
        row(body, "缓存管理", "音乐 " + cacheLimit(false) + " · 有声书 " + cacheLimit(true), "", () -> open(this::cacheSettings));
        section("服务器下载");
        var server = settingsCard(body);
        var enabled = settingSwitch("启用推送到服务器", prefs.getBoolean("server-enabled", false));
        fill(server, enabled);
        gap(server, 12);
        var address = labeledField(server, "下载服务地址", "https://nas.example.com", prefs.getString("server-url", ""),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        gap(server, 12);
        var secret = labeledField(server, "服务令牌", "输入令牌", prefs.getString("server-token", ""),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        gap(server, 12);
        var row = line();
        row.addView(button("保存", true, () -> {
            try {
                String url = address.getText().toString().trim();
                if (enabled.isChecked() || !url.trim().isEmpty()) url = Http.base(url);
                String token = secret.getText().toString().trim();
                if (enabled.isChecked() && token.length() < 32) throw new IllegalArgumentException("请填写下载服务的令牌");
                prefs.edit().putString("server-url", url).putString("server-token", token)
                    .putBoolean("server-enabled", enabled.isChecked()).apply();
                toast("已保存");
            } catch (Exception error) { toast(error.getMessage()); }
        }));
        space(row, 8);
        row.addView(button("测试连接", false, () -> {
            String url = address.getText().toString(), token = secret.getText().toString().trim();
            work(() -> Http.json(Http.base(url) + "/health", Map.of("Authorization", "Bearer " + token), null),
                (value, error) -> toast(error == null ? "下载服务连接成功" : error));
        }));
        fill(server, row);
        gap(body, 8);
        row(body, "服务器任务", "查看音乐与有声书下载进度", "", () -> open(this::jobs));
        section("应用");
        row(body, "关于", "版本与 GitHub", "", UiIcon.Kind.INFO, () -> open(this::about));
    }

    private String appVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null || version.isEmpty() ? "未知" : version;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return "未知"; }
    }

    private void about() {
        page("音浪", "关于");
        var identity = column();
        identity.setGravity(Gravity.CENTER_HORIZONTAL);
        identity.setPadding(dp(16), dp(24), dp(16), dp(24));
        var logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(logo, new LinearLayout.LayoutParams(dp(80), dp(80)));
        gap(identity, 16);
        identity.addView(text("音浪", 27, INK, true));
        gap(identity, 6);
        identity.addView(text("YinLang", 15, MUTED, false));
        fill(body, identity);
        var info = preferenceGroup();
        preferenceRow(info, UiIcon.Kind.INFO, "版本号", "", appVersion(), null);
        row(body, "GitHub", "github.com/tbslantian", "", UiIcon.Kind.PLUGIN, () -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tbslantian")));
            } catch (android.content.ActivityNotFoundException ignored) { toast("未找到可打开链接的浏览器"); }
        });
    }

    private void chooseAppearance() {
        Appearance[] modes = Appearance.values();
        String[] names = java.util.Arrays.stream(modes).map(mode -> mode.label).toArray(String[]::new);
        dialog().setTitle("首页与页面外观").setSingleChoiceItems(names, appearance().ordinal(), (picker, choice) -> {
            picker.dismiss();
            prefs.edit().putString("appearance-mode", modes[choice].name()).apply();
            refreshAppearance(true);
        }).setNegativeButton("取消", null).show();
    }

    private void materialSettingsPage(String title) {
        page("", title);
        int background = darkAppearance ? 0xff14151d : 0xfffaf9ff;
        root.setBackgroundColor(background);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (root.getChildAt(0) instanceof LinearLayout header && header.getChildCount() > 0)
            header.getChildAt(0).setBackground(null);
    }

    private LinearLayout preferenceGroup() {
        var card = column();
        card.setPadding(dp(2), 0, dp(2), 0);
        card.setBackground(shape(darkAppearance ? 0xff20232e : 0xfff1f2f8, 22));
        fill(body, card);
        gap(body, 18);
        return card;
    }

    private void preferenceRow(LinearLayout card, UiIcon.Kind icon, String label, String detail, String value, Runnable action) {
        if (card.getChildCount() > 0) {
            var divider = new View(this);
            divider.setBackgroundColor(darkAppearance ? 0xff3b3e48 : 0xffdedfe6);
            var size = new LinearLayout.LayoutParams(-1, dp(1));
            size.setMargins(dp(58), 0, 0, 0);
            card.addView(divider, size);
        }
        var row = line();
        row.setPadding(dp(16), dp(17), dp(16), dp(17));
        row.setMinimumHeight(dp(68));
        row.addView(new UiIcon(this, icon, 25, MUTED), new LinearLayout.LayoutParams(dp(32), dp(32)));
        space(row, 12);
        var names = column();
        fill(names, text(label, 17, INK, false));
        if (!detail.isEmpty()) { gap(names, 5); fill(names, text(detail, 13, MUTED, false)); }
        weight(row, names);
        if (!value.isEmpty()) {
            var selected = text(value, 14, MUTED, false);
            selected.setMaxWidth(dp(118));
            selected.setMaxLines(2);
            selected.setGravity(Gravity.END);
            row.addView(selected);
            space(row, 9);
        }
        if (action != null) {
            row.addView(new UiIcon(this, UiIcon.Kind.SORT, 17, MUTED), new LinearLayout.LayoutParams(dp(18), dp(24)));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, shape(Color.WHITE, 22)));
            action(row, action);
        }
        fill(card, row);
    }

    private void preferenceChoice(String title, String[] names, int selected, java.util.function.IntConsumer changed) {
        var picker = new android.app.Dialog(this);
        var sheet = column();
        sheet.setPadding(dp(18), dp(12), dp(18), dp(18));
        var grip = new View(this);
        grip.setBackground(shape(MUTED, 4));
        var gripSize = new LinearLayout.LayoutParams(dp(32), dp(4));
        gripSize.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(grip, gripSize);
        gap(sheet, 24);
        fill(sheet, text(title, 24, INK, true));
        gap(sheet, 18);
        var scroll = new ScrollView(this);
        var choices = column();
        scroll.addView(choices);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            var row = line();
            row.setPadding(dp(10), dp(18), dp(10), dp(18));
            row.addView(text(i == selected ? "✓" : "", 23, ACCENT, true), new LinearLayout.LayoutParams(dp(40), -2));
            weight(row, text(names[i], 18, INK, false));
            action(row, () -> { picker.dismiss(); changed.accept(index); });
            fill(choices, row);
        }
        picker.setContentView(sheet);
        showBottomSheet(picker, sheet, Math.min(.72f, .22f + names.length * .075f));
    }

    private void personalization() {
        materialSettingsPage("个性化");
        section("首页");
        var homepage = preferenceGroup();
        boolean rotateHome = HomeTemplate.AUTO.equals(prefs.getString(HomeTemplate.KEY, HomeTemplate.CLASSIC.name()));
        preferenceRow(homepage, UiIcon.Kind.ALBUM, "首页模板", "经典影像 / 留白画廊 / 声波电台",
            rotateHome ? "自动轮换" : currentHomeTemplate().label, this::chooseHomeTemplate);
        if (rotateHome) {
            boolean hourly = "hourly".equals(prefs.getString(HomeTemplate.ROTATION_KEY, "daily"));
            preferenceRow(homepage, UiIcon.Kind.TIMER, "轮换频率", hourly ? "每到整点切换下一套" : "每天零点切换下一套",
                hourly ? "每小时" : "每天", () -> preferenceChoice("首页轮换频率", new String[]{"每小时", "每天"}, hourly ? 0 : 1, i -> {
                    prefs.edit().putString(HomeTemplate.ROTATION_KEY, i == 0 ? "hourly" : "daily").apply();
                    refresh();
                }));
            preferenceRow(homepage, UiIcon.Kind.DISCOVER, "当前模板", "三套模板按顺序轮换", currentHomeTemplate().label, null);
        }
        section("外观");
        var appearance = preferenceGroup();
        String theme = prefs.getString("personal-theme", "default");
        preferenceRow(appearance, UiIcon.Kind.ALBUM, "主题", "", theme.equals("transparent") ? "透明" : "默认", () ->
            preferenceChoice("主题", new String[]{"默认", "透明"}, theme.equals("transparent") ? 1 : 0, i -> {
                prefs.edit().putString("personal-theme", i == 0 ? "default" : "transparent").apply(); refreshAppearance(true);
            }));
        String brightness = prefs.getString("appearance-mode", "LIGHT");
        String[] modes = {"SYSTEM", "LIGHT", "DARK", "AUTO"};
        String[] labels = {"跟随系统", "浅色", "深色", "按时间切换"};
        int selected = java.util.Arrays.asList(modes).indexOf(brightness);
        preferenceRow(appearance, UiIcon.Kind.SUN, "亮度", "", labels[Math.max(0, selected)], () ->
            preferenceChoice("亮度", labels, Math.max(0, selected), i -> {
                prefs.edit().putString("appearance-mode", modes[i]).apply(); refreshAppearance(true);
            }));
        String accent = prefs.getString("personal-accent", "original");
        String[] accents = {"original", "blue", "green"};
        String[] accentNames = {"音浪原色", "深海蓝", "溪流绿"};
        int selectedAccent = Math.max(0, java.util.Arrays.asList(accents).indexOf(accent));
        preferenceRow(appearance, UiIcon.Kind.SLIDERS, "主题色", "", accentNames[selectedAccent], () ->
            preferenceChoice("主题色", accentNames, selectedAccent, i -> {
                prefs.edit().putString("personal-accent", accents[i]).apply(); refreshAppearance(true);
            }));
        section("播放条");
        var playback = preferenceGroup();
        String[] lyricValues = {"off", "single", "double"};
        String[] lyricLabels = {"关闭", "单行歌词", "双行歌词"};
        int lyric = Math.max(0, java.util.Arrays.asList(lyricValues).indexOf(prefs.getString("mini-lyrics", "off")));
        preferenceRow(playback, UiIcon.Kind.LYRICS, "显示歌词", "", lyricLabels[lyric], () ->
            preferenceChoice("显示歌词", lyricLabels, lyric, i -> { prefs.edit().putString("mini-lyrics", lyricValues[i]).apply(); refresh(); }));
        section("发现页");
        var discover = preferenceGroup();
        boolean albums = prefs.getString("discover-kind", "songs").equals("albums");
        preferenceRow(discover, UiIcon.Kind.SONG, "推荐类型", "", albums ? "专辑" : "歌曲", () ->
            preferenceChoice("推荐类型", new String[]{"歌曲", "专辑"}, albums ? 1 : 0, i -> {
                prefs.edit().putString("discover-kind", i == 0 ? "songs" : "albums").apply(); refresh();
            }));
    }

    private void chooseHomeTemplate() {
        var picker = new android.app.Dialog(this);
        var sheet = column();
        sheet.setPadding(dp(18), dp(16), dp(18), dp(20));
        var heading = line();
        weight(heading, text("首页模板", 24, INK, true));
        heading.addView(iconButton(UiIcon.Kind.CLOSE, "关闭", false, 44, 20, false, picker::dismiss));
        fill(sheet, heading);
        gap(sheet, 8);
        fill(sheet, text("选择喜欢的风格，返回首页即可看到。", 13, MUTED, false));
        gap(sheet, 18);
        var scroll = new ScrollView(this);
        var choices = column();
        scroll.addView(choices);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        String mode = prefs.getString(HomeTemplate.KEY, HomeTemplate.CLASSIC.name());
        boolean automatic = HomeTemplate.AUTO.equals(mode);
        for (HomeTemplate template : HomeTemplate.values()) {
            boolean selected = !automatic && HomeTemplate.read(mode) == template;
            var option = line();
            option.setPadding(dp(14), dp(14), dp(14), dp(14));
            var names = column();
            fill(names, text((selected ? "✓  " : "") + template.label, 17, selected ? ACCENT : INK, true));
            gap(names, 7);
            fill(names, text(template.description, 12, MUTED, false));
            weight(option, names);
            space(option, 8);
            option.addView(homeTemplatePreview(template), new LinearLayout.LayoutParams(dp(108), dp(82)));
            clickable(option, selected ? ACCENT_TINT : PAPER, 18, () -> {
                prefs.edit().putString(HomeTemplate.KEY, template.name()).apply();
                picker.dismiss();
                refresh();
            });
            option.setContentDescription(template.label + "，" + template.description + (selected ? "，已选中" : ""));
            fill(choices, option);
            gap(choices, 10);
        }
        var automaticChoice = line();
        automaticChoice.setPadding(dp(14), dp(16), dp(14), dp(16));
        var names = column();
        fill(names, text((automatic ? "✓  " : "") + "自动轮换", 17, automatic ? ACCENT : INK, true));
        gap(names, 7);
        fill(names, text("三套模板依次切换，可选每小时或每天", 12, MUTED, false));
        weight(automaticChoice, names);
        space(automaticChoice, 10);
        automaticChoice.addView(new UiIcon(this, UiIcon.Kind.REFRESH, 27, automatic ? ACCENT : MUTED),
            new LinearLayout.LayoutParams(dp(40), dp(44)));
        clickable(automaticChoice, automatic ? ACCENT_TINT : PAPER, 18, () -> {
            prefs.edit().putString(HomeTemplate.KEY, HomeTemplate.AUTO).apply();
            picker.dismiss();
            refresh();
        });
        fill(choices, automaticChoice);
        picker.setContentView(sheet);
        showBottomSheet(picker, sheet, .8f);
    }

    private LinearLayout homeTemplatePreview(HomeTemplate template) {
        var preview = column();
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (boolean books : new boolean[]{false, true}) {
            var tile = new FrameLayout(this);
            int surface = template == HomeTemplate.GALLERY ? books ? 0xffeee0cd : 0xffdce7db
                : books ? 0xff854256 : 0xff204966;
            tile.setBackground(shape(surface, 7));
            tile.setClipToOutline(true);
            if (template == HomeTemplate.CLASSIC) {
                var image = new ImageView(this);
                image.setImageBitmap(homeBitmap(books ? R.drawable.home_books_light : R.drawable.home_music_light));
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
                var shade = new View(this);
                shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x99000000, 0x10000000}));
                tile.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            } else tile.addView(new HomeEntryArtwork(this, template, books, false), new FrameLayout.LayoutParams(dp(44), -1, Gravity.RIGHT));
            var label = text(books ? "有声书" : "音乐", 10, template == HomeTemplate.GALLERY ? 0xff354638 : Color.WHITE, true);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(dp(9), 0, 0, 0);
            tile.addView(label, new FrameLayout.LayoutParams(-1, -1));
            preview.addView(tile, new LinearLayout.LayoutParams(-1, 0, 1));
            if (!books) gap(preview, 5);
        }
        return preview;
    }

    private void libraryAudioQuality() {
        materialSettingsPage("在线播放音质");
        var networks = preferenceGroup();
        for (boolean wifi : new boolean[]{false, true}) {
            var quality = LibraryAudioSettings.quality(prefs, wifi);
            preferenceRow(networks, wifi ? UiIcon.Kind.SERVER : UiIcon.Kind.CHART, wifi ? "Wi‑Fi" : "移动网络",
                quality.description(), quality.label, () -> {
                    var values = LibraryAudioSettings.Quality.values();
                    preferenceChoice(wifi ? "Wi‑Fi 音质" : "移动网络音质",
                        java.util.Arrays.stream(values).map(q -> q.label + (q.kbps > 0 ? " · " + q.kbps + " kbps" : "")).toArray(String[]::new),
                        quality.ordinal(), i -> { prefs.edit().putString(wifi ? LibraryAudioSettings.WIFI_KEY : LibraryAudioSettings.MOBILE_KEY, values[i].name()).apply(); refresh(); });
                });
        }
        var formats = preferenceGroup();
        var format = LibraryAudioSettings.format(prefs);
        preferenceRow(formats, UiIcon.Kind.SONG, "转码格式", "", format.label, () -> {
            var values = LibraryAudioSettings.Format.values();
            preferenceChoice("转码格式", java.util.Arrays.stream(values).map(f -> f.label).toArray(String[]::new), format.ordinal(), i -> {
                prefs.edit().putString(LibraryAudioSettings.FORMAT_KEY, values[i].name()).apply(); refresh();
            });
        });
        note(body, "AAC 格式可能需要服务端额外配置才能正常播放。");
        note(body, "适用于音乐库在线播放，下一首或重新播放时生效。无损表示按原文件播放；原文件若为有损格式，不会提升为无损。");
    }

    private void libraryDiscover() {
        page("音乐库", "发现", this::showLibraryOptions);
        if (prefs.getString("nav-url", "").isBlank()) {
            note(body, "连接音乐库后查看推荐。");
            fill(body, button("添加服务器", true, () -> open(this::addLibraryServer)));
            return;
        }
        fill(body, text(libraryDisplayName() + " · 你的私人音乐库", 13, MUTED, false));
        gap(body, 10);
        searchEntry("在音乐库中搜索", () -> open(() -> musicSearch(true)));
        var sections = prefs.getString("discover-kind", "songs").equals("albums")
            ? new Navidrome.DiscoverySection[]{Navidrome.DiscoverySection.RANDOM, Navidrome.DiscoverySection.FREQUENT,
                Navidrome.DiscoverySection.SONGS, Navidrome.DiscoverySection.NEWEST}
            : new Navidrome.DiscoverySection[]{Navidrome.DiscoverySection.SONGS, Navidrome.DiscoverySection.FREQUENT,
                Navidrome.DiscoverySection.RANDOM, Navidrome.DiscoverySection.NEWEST};
        for (var section : sections) new LibraryDiscoveryBlock(section);
    }

    private final class LibraryDiscoveryBlock {
        private final Navidrome.DiscoverySection section;
        private final int pageId = generation;
        private final String account = sources.navCacheKey();
        private final LinearLayout results = column();
        private final TextView status = text("", 12, MUTED, false);
        private final TextView more;
        private final FrameLayout reload, play;
        private List<JSONObject> entries = List.of();
        private boolean loading;

        LibraryDiscoveryBlock(Navidrome.DiscoverySection section) {
            this.section = section;
            gap(body, 22);
            var heading = line();
            var title = text(section.title, 20, INK, true);
            title.setAccessibilityHeading(true);
            weight(heading, title);
            more = discoveryTextAction("查看全部", () -> {
                var snapshot = new ArrayList<>(entries);
                open(() -> libraryDiscoveryList(section, snapshot));
            });
            more.setEnabled(false);
            more.setAlpha(.4f);
            heading.addView(more);
            if (section == Navidrome.DiscoverySection.SONGS) {
                play = iconButton(UiIcon.Kind.PLAY, "播放随心听的全部歌曲", false, 44, 18, false,
                    () -> playPlaylist(entries.stream().map(MediaEntry::library).collect(Collectors.toList()), false));
                play.setEnabled(false);
                play.setAlpha(.4f);
                heading.addView(play);
            } else play = null;
            reload = iconButton(UiIcon.Kind.REFRESH, section.random() ? section.title + "换一批" : "刷新" + section.title,
                false, 44, 19, false, () -> load(true));
            heading.addView(reload);
            fill(body, heading);
            fill(body, text(section.subtitle, 12, MUTED, false));
            gap(body, 12);
            fill(body, results);
            status.setVisibility(View.GONE);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            fill(body, status);
            load(false);
        }

        private void load(boolean fresh) {
            if (loading) return;
            loading = true;
            reload.setEnabled(false);
            reload.setAlpha(.35f);
            reload.setContentDescription("正在加载" + section.title);
            status.setVisibility(View.GONE);
            if (entries.isEmpty()) {
                results.removeAllViews();
                note(results, "正在读取音乐库…");
            }
            work(() -> playlists.navDiscovery(section, fresh), (value, error) -> {
                if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
                loading = false;
                reload.setEnabled(true);
                reload.setAlpha(1f);
                reload.setContentDescription(section.random() ? section.title + "换一批" : "刷新" + section.title);
                if (error != null) {
                    if (entries.isEmpty()) results.removeAllViews();
                    status.setText("加载失败，点刷新重试 · " + error);
                    status.setVisibility(View.VISIBLE);
                    return;
                }
                entries = objects((JSONArray) value).stream().filter(item -> !item.optString("id").isBlank()).collect(Collectors.toList());
                results.removeAllViews();
                more.setEnabled(!entries.isEmpty());
                more.setAlpha(entries.isEmpty() ? .4f : 1f);
                if (play != null) {
                    play.setEnabled(!entries.isEmpty());
                    play.setAlpha(entries.isEmpty() ? .4f : 1f);
                }
                if (entries.isEmpty()) {
                    note(results, section == Navidrome.DiscoverySection.FREQUENT
                        ? "还没有常听记录。多听几张专辑，再回来看看。" : "音乐库里暂时没有推荐内容。");
                } else if (section == Navidrome.DiscoverySection.SONGS) {
                    discoverySongs(results, entries, 4);
                } else if (section == Navidrome.DiscoverySection.FREQUENT) {
                    discoveryAlbumRows(results, entries.subList(0, Math.min(3, entries.size())), true);
                } else discoveryAlbumShelf(results, entries.subList(0, Math.min(10, entries.size())));
            });
        }
    }

    private TextView discoveryTextAction(String title, Runnable select) {
        var control = text(title, 12, ACCENT, true);
        control.setGravity(Gravity.CENTER);
        control.setPadding(dp(9), dp(8), dp(9), dp(8));
        control.setMinHeight(dp(44));
        clickable(control, Color.TRANSPARENT, 12, select);
        return control;
    }

    private FrameLayout discoveryArtwork(JSONObject item, int size) {
        var frame = new FrameLayout(this);
        frame.setBackground(shape(SOFT, size > 80 ? 14 : 11));
        frame.setClipToOutline(true);
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        frame.addView(new UiIcon(this, UiIcon.Kind.ALBUM, size > 80 ? 36 : 22, MUTED), new FrameLayout.LayoutParams(-1, -1));
        String address = libraryCover(item.optString("coverArt"), CoverThumbnail.edge(dp(size)));
        if (!address.isEmpty()) {
            var image = catalogArt(address, size);
            image.setBackground(null);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        }
        return frame;
    }

    private void discoverySongs(LinearLayout target, List<JSONObject> rows, int limit) {
        var tracks = rows.stream().map(MediaEntry::library).collect(Collectors.toList());
        for (int i = 0; i < Math.min(limit, tracks.size()); i++) {
            int index = i;
            MediaEntry song = tracks.get(i);
            var item = line();
            item.setPadding(0, dp(8), 0, dp(8));
            item.setMinimumHeight(dp(68));
            item.addView(discoveryArtwork(song.raw(), 44), new LinearLayout.LayoutParams(dp(44), dp(44)));
            space(item, 10);
            var labels = column();
            var title = text(song.title(), 15, INK, true);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, title);
            gap(labels, 5);
            var detail = line();
            String format = song.raw().optString("suffix").toUpperCase(Locale.ROOT);
            int bitrate = song.raw().optInt("bitRate");
            if (!format.isEmpty()) {
                String quality = format + (bitrate > 0 ? " " + bitrate + "K" : "");
                detail.addView(badge(quality, ACCENT, ACCENT_TINT));
                space(detail, 5);
            }
            var artist = text(song.subtitle(), 12, MUTED, false);
            artist.setSingleLine();
            artist.setEllipsize(TextUtils.TruncateAt.END);
            weight(detail, artist);
            fill(labels, detail);
            weight(item, labels);
            int duration = TrackSort.seconds(song);
            if (duration > 0 && getResources().getConfiguration().screenWidthDp >= 360) {
                var time = text(String.format(Locale.ROOT, "%d:%02d", duration / 60, duration % 60), 11, MUTED, false);
                time.setGravity(Gravity.END);
                item.addView(time, new LinearLayout.LayoutParams(dp(34), -2));
            }
            boolean saved = serverFavorite(song);
            var heart = iconButton(saved ? UiIcon.Kind.HEART_FILLED : UiIcon.Kind.HEART,
                saved ? "取消服务器收藏" : "添加服务器收藏", false, 44, 19, false, () -> {});
            heart.setOnClickListener(view -> toggleServerFavorite(song, heart));
            item.addView(heart);
            item.addView(iconButton(UiIcon.Kind.ELLIPSIS, song.title() + "的更多操作", false, 44, 19, false,
                () -> songActions(song, false, "", index + 1, null)));
            clickable(item, Color.TRANSPARENT, 12, () -> PlaybackService.queue(this, tracks, index, false));
            fill(target, item);
            if (i + 1 < Math.min(limit, tracks.size())) discoveryDivider(target);
        }
    }

    private void discoveryDivider(LinearLayout target) {
        var divider = new View(this);
        divider.setBackgroundColor(SOFT);
        var placement = new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f)));
        placement.setMarginStart(dp(54));
        target.addView(divider, placement);
    }

    private void discoveryAlbumRows(LinearLayout target, List<JSONObject> albums, boolean ranked) {
        int pageId = generation;
        String account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        for (int i = 0; i < albums.size(); i++) {
            JSONObject album = albums.get(i);
            String title = MediaEntry.first(album, "name", "title", "album");
            var item = line();
            item.setPadding(0, dp(9), 0, dp(9));
            item.addView(discoveryArtwork(album, 50), new LinearLayout.LayoutParams(dp(50), dp(50)));
            space(item, 11);
            var labels = column();
            var name = text((ranked ? (i + 1) + "  " : "") + title, 15, INK, true);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, name);
            gap(labels, 4);
            String detail = album.optString("artist");
            int count = album.optInt("songCount"), plays = album.optInt("playCount");
            if (count > 0) detail += (detail.isEmpty() ? "" : " · ") + count + " 首";
            if (ranked && plays > 0) detail += " · 播放 " + plays + " 次";
            var subtitle = text(detail, 12, MUTED, false);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, subtitle);
            weight(item, labels);
            var play = iconButton(UiIcon.Kind.PLAY, "播放专辑 " + title, false, 44, 18, false, () -> {});
            play.setOnClickListener(view -> {
                if (!play.isEnabled()) return;
                play.setEnabled(false);
                play.setAlpha(.35f);
                work(() -> server.album(album.optString("id")), (value, error) -> {
                    if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
                    play.setEnabled(true);
                    play.setAlpha(1f);
                    if (error != null) { toast(error); return; }
                    var tracks = objects((JSONArray) value).stream().map(MediaEntry::library).collect(Collectors.toList());
                    if (tracks.isEmpty()) toast("此专辑暂无歌曲");
                    else playPlaylist(tracks, false);
                });
            });
            item.addView(play);
            clickable(item, Color.TRANSPARENT, 12, () -> openLibraryDiscoveryAlbum(album));
            fill(target, item);
            if (i + 1 < albums.size()) discoveryDivider(target);
        }
    }

    private void discoveryAlbumShelf(LinearLayout target, List<JSONObject> albums) {
        var scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        var strip = line();
        strip.setGravity(Gravity.TOP);
        int size = Math.max(116, Math.min(144, Math.round((getResources().getConfiguration().screenWidthDp - 32) * .4f)));
        for (int i = 0; i < albums.size(); i++) {
            JSONObject album = albums.get(i);
            var card = column();
            card.addView(discoveryArtwork(album, size), new LinearLayout.LayoutParams(dp(size), dp(size)));
            gap(card, 8);
            String name = MediaEntry.first(album, "name", "title", "album");
            var title = text(name, 14, INK, true);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            fill(card, title);
            gap(card, 3);
            var artist = text(album.optString("artist"), 12, MUTED, false);
            artist.setSingleLine();
            artist.setEllipsize(TextUtils.TruncateAt.END);
            fill(card, artist);
            card.setPadding(0, 0, 0, dp(5));
            card.setContentDescription(name + "，" + album.optString("artist"));
            card.setScreenReaderFocusable(true);
            clickable(card, Color.TRANSPARENT, 14, () -> openLibraryDiscoveryAlbum(album));
            strip.addView(card, new LinearLayout.LayoutParams(dp(size), -2));
            if (i + 1 < albums.size()) space(strip, 14);
        }
        scroll.addView(strip);
        var viewport = coverViewport;
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (viewport != null) viewport.checkVisible();
        });
        fill(target, scroll);
    }

    private void openLibraryDiscoveryAlbum(JSONObject album) {
        open(() -> {
            page("音乐库 / 专辑", MediaEntry.first(album, "name", "title", "album"));
            librarySongs(body, -1, unused -> sources.navidrome().album(album.optString("id")));
        });
    }

    private void libraryDiscoveryList(Navidrome.DiscoverySection section, List<JSONObject> snapshot) {
        page("音乐库 / 发现", section.title);
        fill(body, text(section.subtitle, 13, MUTED, false));
        gap(body, 12);
        if (section == Navidrome.DiscoverySection.SONGS) {
            var tracks = snapshot.stream().map(MediaEntry::library).collect(Collectors.toList());
            if (!tracks.isEmpty()) fill(body, button("播放全部 · " + tracks.size() + " 首", true, () -> playPlaylist(tracks, false)));
            gap(body, 10);
            discoverySongs(body, snapshot, snapshot.size());
        } else {
            discoveryAlbumRows(body, snapshot, false);
            if (!section.random() && snapshot.size() == 20) {
                var controls = column();
                fill(body, controls);
                fill(controls, button("加载更多专辑", false, () -> loadDiscoveryAlbums(section, body, controls, 20)));
            }
        }
    }

    private void loadDiscoveryAlbums(Navidrome.DiscoverySection section, LinearLayout target, LinearLayout controls, int offset) {
        int pageId = generation;
        String account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        controls.removeAllViews();
        note(controls, "正在读取更多专辑…");
        work(() -> server.discovery(section, offset, 50), (value, error) -> {
            if (!current(pageId) || !account.equals(sources.navCacheKey())) return;
            controls.removeAllViews();
            if (error != null) {
                note(controls, error);
                fill(controls, button("重试", false, () -> loadDiscoveryAlbums(section, target, controls, offset)));
                return;
            }
            var albums = objects((JSONArray) value);
            target.removeView(controls);
            discoveryAlbumRows(target, albums, false);
            fill(target, controls);
            if (albums.size() == 50) fill(controls, button("加载更多专辑", false,
                () -> loadDiscoveryAlbums(section, target, controls, offset + 50)));
        });
    }

    private void homeSections() { homeContent(); }

    private static String clockLabel(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private void chooseAppearanceTime(boolean dark) {
        int start = appearanceStart(dark);
        var picker = new android.app.TimePickerDialog(this, darkAppearance ? android.R.style.Theme_Material_Dialog_Alert
            : android.R.style.Theme_Material_Light_Dialog_Alert, (view, hour, minute) -> {
            int requested = hour * 60 + minute;
            if (requested == appearanceStart(!dark)) { toast("浅色与深色的切换时间需要不同"); return; }
            prefs.edit().putInt(dark ? "appearance-dark-start" : "appearance-light-start", requested).apply();
            refreshAppearance(true);
        }, start / 60, start % 60, true);
        picker.show();
    }
    private String cacheLimit(boolean book) {
        int limit = mediaCache.limitMb(book);
        return limit == 0 ? "已关闭" : limit + " MB";
    }
    private void cacheSettings() {
        page("总设置", "缓存管理");
        note(body, "自动清理最久未使用的缓存。手动保存的离线下载独立存放。");
        cachePanel(false, "音乐");
        cachePanel(true, "有声书");
    }
    private void cachePanel(boolean book, String label) {
        section(label);
        var card = settingsCard(body);
        int id = generation;
        TextView usage = text("正在统计缓存…", 14, MUTED, false);
        fill(card, usage);
        work(() -> mediaCache.bucket(book).size(), (value, error) -> {
            if (!current(id) || error != null) return;
            usage.setText(String.format(Locale.ROOT, "已用 %.1f MB   /   %s", (Long) value / (double) MediaCache.MB, cacheLimit(book)));
        });
        gap(card, 10);
        var controls = line();
        controls.addView(button("设置上限", false, () -> cacheLimitDialog(book, label)));
        space(controls, 8);
        controls.addView(button("立即清理", false, () -> {
            work(() -> { mediaCache.bucket(book).clear(); return null; }, (value, error) -> {
                covers.evictAll();
                if (current(id)) { toast(error == null ? "已清理" + label + "缓存" : error); refresh(); }
            });
        }));
        fill(card, controls);
    }
    private void cacheLimitDialog(boolean book, String label) {
        var panel = column();
        panel.setPadding(dp(20), dp(10), dp(20), 0);
        var input = field("输入 MB，0 为关闭", Integer.toString(mediaCache.limitMb(book)), InputType.TYPE_CLASS_NUMBER);
        fill(panel, input);
        var dialog = dialog().setTitle(label + "缓存上限（MB）").setView(panel)
            .setMessage("默认 512 MB，可设为 0–16384 MB。").setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int value = Integer.parseInt(input.getText().toString().trim());
                if (value < 0 || value > 16384) throw new NumberFormatException();
                int id = generation;
                dialog.dismiss();
                work(() -> { mediaCache.setLimit(book, value); return null; }, (result, error) -> {
                    covers.evictAll();
                    if (current(id)) refresh();
                });
            } catch (NumberFormatException error) { input.setError("请输入 0–16384"); }
        }));
        dialog.show();
    }
    private void showUpdateCount() {
        int count = updates.count();
        if (homeSettings != null) homeSettings.setContentDescription(count == 0 ? "设置" : "设置，" + count + " 个音源有更新");
        if (updateLabel != null) {
            String latest = prefs.getString("source-last-update", "");
            updateLabel.setVisibility(count == 0 && (sourceRows == null || latest.isEmpty()) ? View.GONE : View.VISIBLE);
            updateLabel.setText(count > 0 ? count + " 个音源有更新" : latest);
            updateLabel.setPadding(dp(12), dp(5), 0, dp(7));
        }
        if (sourceRows != null) renderSourceRows();
    }
    private void sourceSettings() {
        page("音乐设置", "音源更新");
        var card = settingsCard(body);
        var toggle = settingSwitch("自动更新已配置地址的音源", updates.automatic());
        toggle.setOnCheckedChangeListener((view, enabled) -> updates.setAutomatic(enabled));
        fill(card, toggle);
        gap(card, 8);
        int id = generation;
        fill(card, button("立即检查所有音源", true, () -> {
            toast("正在检查音源更新…");
            updates.check(true, report -> {
                if (!current(id)) return;
                toast(report.checked() == 0 ? "没有可检查的更新地址" : "检查 " + report.checked() + " 个，发现 " + report.found()
                    + " 个，已更新 " + report.applied() + " 个" + (report.failed() > 0 ? "，失败 " + report.failed() + " 个" : ""));
                refresh();
            });
        }));
        updateLabel = text("", 14, ACCENT, true);
        fill(body, updateLabel);
        section("已安装音源");
        sourceRows = column();
        fill(body, sourceRows);
        showUpdateCount();
    }
    private void renderSourceRows() {
        sourceRows.removeAllViews();
        var items = updates.items();
        if (items.isEmpty()) note(sourceRows, "导入音乐音源或有声书插件后，会在这里显示。");
        for (SourceUpdates.Item item : items) {
            JSONObject notice = updates.notice(item.id());
            String version = item.version().isEmpty() ? "" : " · " + item.version();
            String detail = item.book() ? "有声书插件" : "LX 音源";
            if (notice != null) {
                String available = notice.optString("version");
                detail += available.isEmpty() ? " · 有更新提示" : " · 可更新至 " + available;
            } else detail += item.address().isEmpty() ? " · 未配置更新地址" : " · 已配置更新地址";
            row(sourceRows, item.name(), detail + version, "", () -> sourceActions(item));
        }
    }
    private void sourceActions(SourceUpdates.Item item) {
        JSONObject notice = updates.notice(item.id());
        String message = notice == null ? "" : notice.optString("log");
        var dialog = dialog().setTitle(item.name()).setNegativeButton("关闭", null)
            .setNeutralButton("更新地址", (ignored, index) -> prompt("音源更新地址", item.book() ? "ZIP 文件直链" : "JS 文件直链", item.address(), value -> {
                try { updates.setAddress(item, value); refresh(); }
                catch (Exception error) { toast(error.getMessage()); }
            }))
            .setPositiveButton("立即更新", (ignored, index) -> {
                int id = generation;
                updates.install(item, (value, error) -> {
                    if (!current(id)) return;
                    toast(error != null ? error : "已是最新版本".equals(value) ? (String) value : "已更新 " + item.name());
                    refresh();
                });
            });
        if (!message.isEmpty()) dialog.setMessage(message);
        dialog.show();
    }
    private Map<String, String> serverHeaders() {
        return Map.of("Authorization", "Bearer " + prefs.getString("server-token", ""), "Content-Type", "application/json");
    }
    private void jobs() {
        page("总设置", "服务器任务");
        var actions = line();
        weight(actions, button("刷新", false, this::refresh));
        space(actions, 8);
        weight(actions, button("清除历史", false, this::clearServerHistory));
        fill(body, actions);
        gap(body, 12);
        int id = generation;
        work(() -> Http.json(Http.base(prefs.getString("server-url", "")) + "/v1/jobs", serverHeaders(), null), (value, error) -> {
            if (!current(id)) return;
            if (error != null) { note(body, error); return; }
            List<JSONObject> tasks = objects(array((JSONObject) value, "jobs"));
            if (tasks.isEmpty()) note(body, "服务器还没有下载任务。");
            for (JSONObject job : tasks) {
                String status = switch (job.optString("status")) {
                    case "queued" -> "排队中";
                    case "downloading" -> "下载中 · " + String.format(Locale.ROOT, "%.1f MB", job.optLong("bytes") / 1048576.0);
                    case "completed" -> "已完成";
                    case "paused" -> "已暂停";
                    case "failed" -> "失败 · " + job.optString("error");
                    default -> job.optString("status");
                };
                String quality = qualityLabel(job.optString("quality"));
                String type = "music".equals(job.optString("kind")) ? "音乐" : "有声书";
                row(body, job.optString("title"), type + (quality.isEmpty() ? "" : " · " + quality) + " · " + status, "",
                    () -> dialog().setTitle(job.optString("title")).setMessage(status + "\n" + job.optString("file")).setPositiveButton("关闭", null).show());
            }
        });
    }
    private void clearServerHistory() {
        if (clearingServerHistory) return;
        clearingServerHistory = true;
        int id = generation;
        work(() -> {
            String url = Http.base(prefs.getString("server-url", "")) + "/v1/jobs/history";
            var response = Http.request(url, "DELETE", serverHeaders(), null, false);
            if (response.status() < 200 || response.status() >= 300) {
                String message = "";
                try { message = new JSONObject(response.body()).optString("error"); } catch (Exception ignored) {}
                throw new java.io.IOException("HTTP " + response.status() + (message.isBlank() ? "" : " · " + message));
            }
            return new JSONObject(response.body()).optInt("removed");
        }, (value, error) -> {
            clearingServerHistory = false;
            if (!current(id)) return;
            if (error != null) { toast("清除历史失败：" + error); return; }
            int removed = value instanceof Number count ? count.intValue() : 0;
            toast(removed > 0 ? "已清除 " + removed + " 条历史任务" : "没有可清除的历史任务");
            refresh();
        });
    }
    private void librarySettings(String family, String candidate) {
        materialSettingsPage("配置服务器");
        String stored = prefs.getString("nav-url", "");
        boolean editing = family.equals(libraryServerName()) && (candidate.isEmpty() || candidate.equals(stored));
        var connection = LibraryConnection.parse(candidate.isEmpty() && editing ? stored : candidate);
        section("基本信息");
        var basic = preferenceGroup();
        basic.setPadding(dp(16), dp(16), dp(16), dp(16));
        var nickname = labeledField(basic, "别名", family, editing ? prefs.getString("nav-alias", "") : "", InputType.TYPE_CLASS_TEXT);
        gap(basic, 14);
        var host = labeledField(basic, "主机地址", "example.com", connection.host(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        gap(basic, 14);
        var routing = line();
        var ports = column();
        var paths = column();
        var port = labeledField(ports, "端口", "可选", connection.port(), InputType.TYPE_CLASS_NUMBER);
        var path = labeledField(paths, "路径", "可选", connection.path(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        weight(routing, ports); space(routing, 12); weight(routing, paths); fill(basic, routing);
        gap(basic, 12);
        var https = settingSwitch("使用 HTTPS", connection.https());
        fill(basic, https);
        var preview = text("", 13, MUTED, false);
        preview.setTextIsSelectable(true);
        gap(basic, 12); fill(basic, preview);
        java.util.function.Supplier<String> address = () -> new LibraryConnection(host.getText().toString(), port.getText().toString(), path.getText().toString(), https.isChecked()).url();
        Runnable updateAddress = () -> {
            try { preview.setText("连接地址：" + address.get()); }
            catch (Exception error) { preview.setText(error.getMessage()); }
        };
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { updateAddress.run(); }
            public void afterTextChanged(android.text.Editable text) {}
        };
        for (var field : List.of(host, port, path)) field.addTextChangedListener(watcher);
        https.setOnCheckedChangeListener((view, checked) -> updateAddress.run());
        host.setOnFocusChangeListener((view, focused) -> {
            if (!focused && host.getText().toString().contains("://")) {
                var pasted = LibraryConnection.parse(host.getText().toString().trim());
                if (!pasted.host().contains("://")) {
                    host.setText(pasted.host()); port.setText(pasted.port()); path.setText(pasted.path()); https.setChecked(pasted.https());
                    updateAddress.run();
                }
            }
        });
        updateAddress.run();
        section("登录信息");
        var credentials = preferenceGroup();
        credentials.setPadding(dp(16), dp(16), dp(16), dp(16));
        var user = labeledField(credentials, "用户名", "输入用户名", editing ? prefs.getString("nav-user", "") : "", InputType.TYPE_CLASS_TEXT);
        gap(credentials, 14);
        var password = labeledField(credentials, "密码", "输入密码", editing ? prefs.getString("nav-password", "") : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        var visible = settingSwitch("显示密码", false);
        visible.setOnCheckedChangeListener((view, checked) -> {
            int cursor = password.getSelectionStart();
            password.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
            password.setSelection(Math.max(0, Math.min(cursor, password.length())));
        });
        fill(credentials, visible);
        var failure = text("", 14, ACCENT, false); fill(body, failure);
        var login = button("登录", true, () -> {});
        login.setOnClickListener(view -> {
            final String url;
            try { url = address.get(); }
            catch (Exception error) { failure.setText(error.getMessage()); return; }
            String name = user.getText().toString().trim(), pass = password.getText().toString();
            if (name.isEmpty()) { user.setError("请输入用户名"); user.requestFocus(); return; }
            if (pass.isEmpty()) { password.setError("请输入密码"); password.requestFocus(); return; }
            int id = generation;
            login.setEnabled(false); login.setText("正在登录…"); failure.setText("");
            work(() -> new Navidrome(url, name, pass).get("ping", ""), (value, error) -> {
                if (!current(id)) return;
                login.setEnabled(true); login.setText("登录");
                if (error != null) { failure.setText(error); return; }
                prefs.edit().putString("nav-url", url).putString("nav-user", name).putString("nav-password", pass)
                    .putString("nav-family", family).putString("nav-alias", nickname.getText().toString().trim()).apply();
                getSystemService(InputMethodManager.class).hideSoftInputFromWindow(password.getWindowToken(), 0);
                toast(family + " 已连接");
                if (!back.isEmpty()) back.pop();
                if (!back.isEmpty()) onBackPressed(); else { screen = this::library; refresh(); }
            });
        });
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            login.performClick(); return true;
        });
        fill(body, login);
        gap(body, 16);
    }
    private void library() {
        page("音乐", "音乐库", this::showLibraryOptions);
        if (prefs.getString("nav-url", "").trim().isEmpty()) {
            body.addView(moduleCard("连接音乐库", "浏览服务器上的歌曲、专辑和歌单", OCEAN, UiIcon.Kind.SERVER, true,
                () -> open(this::addLibraryServer)), new LinearLayout.LayoutParams(-1, dp(144)));
            gap(body, 12);
            fill(body, button("添加服务器", true, () -> open(this::addLibraryServer)));
            return;
        }
        int generationAtLoad = generation;
        Navidrome activeLibrary = sources.navidrome();
        TextView count = text(libraryDisplayName(), 13, MUTED, true);
        fill(body, count);
        gap(body, 8);
        work(activeLibrary::songCount, (value, error) -> {
            if (current(generationAtLoad) && error == null && (Integer) value >= 0) {
                count.setText(libraryDisplayName() + " · " + (Integer) value + " 首歌曲");
            }
        });
        searchEntry("搜索资料库", () -> open(() -> musicSearch(true)));
        gap(body, 10);
        libraryNavigation(activeLibrary, generationAtLoad);
        loadLibraryPlaylists();
    }

    private void libraryNavigation(Navidrome server, int pageId) {
        var shortcuts = column();
        shortcuts.addView(libraryShortcut("歌曲", UiIcon.Kind.SONG, () -> open(this::libraryTracks), null));
        gap(shortcuts, 8);
        shortcuts.addView(libraryShortcut("专辑", UiIcon.Kind.ALBUM, () -> open(this::libraryAlbums), null));
        gap(shortcuts, 8);
        var playFavorites = iconButton(UiIcon.Kind.PLAY, "播放服务器收藏", false, 44, 16, false,
            () -> work(server::favorites, (value, error) -> {
                if (!current(pageId)) return;
                if (error != null) { toast(error); return; }
                var songs = objects((JSONArray) value).stream().map(MediaEntry::library).collect(java.util.stream.Collectors.toList());
                if (songs.isEmpty()) toast("服务器收藏里暂无歌曲");
                else playPlaylist(songs, false);
            }));
        shortcuts.addView(libraryShortcut("我喜欢的", UiIcon.Kind.HEART, () -> open(this::libraryFavorites), playFavorites));

        var entries = line();
        entries.setGravity(Gravity.TOP);
        entries.addView(libraryDiscoveryCard(), new LinearLayout.LayoutParams(0, -1, 1.3f));
        space(entries, 10);
        entries.addView(shortcuts, new LinearLayout.LayoutParams(0, -2, 1));
        // Let the three shortcuts determine the height, including wrapped or enlarged labels.
        fill(body, entries);
        gap(body, 8);
    }

    private LinearLayout libraryShortcut(String title, UiIcon.Kind icon, Runnable select, View trailing) {
        var entry = line();
        entry.setMinimumHeight(dp(56));
        entry.setPadding(dp(10), dp(6), dp(trailing == null ? 12 : 2), dp(6));
        entry.addView(new UiIcon(this, icon, 20, ACCENT), new LinearLayout.LayoutParams(dp(22), dp(32)));
        space(entry, 8);
        var label = text(title, 14, INK, true);
        weight(entry, label);
        if (trailing != null) entry.addView(trailing);
        clickable(entry, PAPER, 18, select);
        if (trailing == null) {
            entry.setContentDescription(title);
            entry.setScreenReaderFocusable(true);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        entry.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return entry;
    }

    private FrameLayout libraryDiscoveryCard() {
        var card = new FrameLayout(this);
        clickable(card, ACCENT, 18, () -> open(this::libraryDiscover));
        card.setClipToOutline(true);
        card.setContentDescription("发现，探索音乐库");

        var record = new ImageView(this);
        record.setImageResource(R.drawable.library_discovery_record);
        record.setScaleType(ImageView.ScaleType.FIT_CENTER);
        record.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        var artwork = new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.TOP | Gravity.END);
        artwork.topMargin = dp(12);
        artwork.setMarginEnd(-dp(12));
        card.addView(record, artwork);

        var labels = column();
        labels.setPadding(dp(16), dp(6), dp(16), dp(22));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        fill(labels, text("发现", 26, ON_ACCENT, true));
        gap(labels, 4);
        var caption = line();
        weight(caption, text("探索音乐库", 13, ON_ACCENT, false));
        var arrow = new UiIcon(this, UiIcon.Kind.ARROW_RIGHT, 19, ON_ACCENT);
        arrow.setRotation(-45);
        caption.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(28)));
        fill(labels, caption);
        card.addView(labels, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setForeground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, shape(PAPER, 18)));
        return card;
    }

    private void showLibraryOptions() {
        var picker = new android.app.Dialog(this);
        var sheet = column();
        sheet.setPadding(dp(18), dp(12), dp(18), dp(20));
        var grip = new View(this);
        grip.setBackground(shape(MUTED, 4));
        var gripSize = new LinearLayout.LayoutParams(dp(32), dp(4));
        gripSize.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(grip, gripSize);
        gap(sheet, 22);
        fill(sheet, text("音乐库设置", 23, INK, true));
        gap(sheet, 16);
        var options = column();
        options.setPadding(dp(2), 0, dp(2), 0);
        options.setBackground(shape(darkAppearance ? 0xff20232e : 0xfff1f2f8, 22));
        preferenceRow(options, UiIcon.Kind.SERVER, "服务器连接", "管理 " + libraryServerName() + " 账号与地址", "", () -> {
            picker.dismiss();
            open(this::addLibraryServer);
        });
        String quality = sources.libraryAudioProfile().label();
        preferenceRow(options, UiIcon.Kind.ALBUM, "在线播放音质", "移动网络与 Wi‑Fi", quality, () -> {
            picker.dismiss();
            open(this::libraryAudioQuality);
        });
        fill(sheet, options);
        picker.setContentView(sheet);
        showBottomSheet(picker, sheet, .46f);
    }

    private String libraryServerName() { return prefs.getString("nav-family", "Navidrome"); }

    private String libraryDisplayName() {
        String alias = prefs.getString("nav-alias", "").trim();
        return alias.isEmpty() ? libraryServerName() : alias;
    }

    private void addLibraryServer() {
        page("音乐库", "添加服务器");
        if (!prefs.getString("nav-url", "").isBlank())
            row(body, "当前服务器 · " + libraryDisplayName(), "编辑连接设置", "", () -> open(() -> librarySettings(libraryServerName(), "")));
        section("局域网发现");
        var results = column();
        fill(body, results);
        body.addView(button("重新发现", false, () -> discoverLibraryServers(results)));
        discoverLibraryServers(results);
        section("手动添加");
        modulePair(moduleCard("Navidrome", "", OCEAN, UiIcon.Kind.ALBUM, false, () -> open(() -> librarySettings("Navidrome", ""))),
            moduleCard("Subsonic", "", CALM_TEAL, UiIcon.Kind.SERVER, false, () -> open(() -> librarySettings("Subsonic", ""))));
    }

    private void stopLibraryDiscovery() {
        if (libraryDiscovery != null) {
            libraryDiscovery.close();
            libraryDiscovery = null;
        }
    }

    private void discoverLibraryServers(LinearLayout results) {
        stopLibraryDiscovery();
        int screen = generation;
        results.removeAllViews();
        note(results, "正在查找局域网中的音乐服务…");
        libraryDiscovery = new LibraryDiscovery(this, servers -> {
            if (!current(screen)) return;
            results.removeAllViews();
            if (servers.isEmpty()) note(results, "未发现广播的音乐服务器，可手动输入地址。");
            for (LibraryDiscovery.Server server : servers) {
                row(results, server.name(), server.address(), "",
                    () -> open(() -> librarySettings(server.family(), server.address())));
            }
        });
        libraryDiscovery.start();
    }

    private void libraryTracks() {
        page("音乐库", "歌曲");
        searchBar("在资料库中搜索", "", query -> { libraryQuery = query; open(() -> musicSearch(true)); });
        librarySongs(body, 0, offset -> sources.navidrome().search("\"\"", offset));
    }

    private void libraryRecentSongs() {
        page("音乐库", "最近添加歌曲");
        note(body, "从服务器的最新专辑中读取歌曲，按入库时间从新到旧排列。");
        var results = column();
        var controls = column();
        fill(body, results);
        fill(body, controls);
        loadRecentSongs(sources.navidrome(), results, controls, new java.util.LinkedHashMap<>(), 0);
    }

    private void loadRecentSongs(Navidrome server, LinearLayout results, LinearLayout controls,
                                 Map<String, MediaEntry> tracks, int offset) {
        int id = generation;
        controls.removeAllViews();
        note(controls, "正在读取最近添加的歌曲…");
        work(() -> server.recentSongs(offset), (value, error) -> {
            if (!current(id)) return;
            controls.removeAllViews();
            if (error != null) {
                note(controls, error);
                fill(controls, button("重试", false, () -> loadRecentSongs(server, results, controls, tracks, offset)));
                return;
            }
            Navidrome.RecentSongs page = (Navidrome.RecentSongs) value;
            for (JSONObject row : objects(page.tracks())) {
                MediaEntry song = MediaEntry.library(row);
                if (!song.id().isEmpty()) tracks.putIfAbsent(song.id(), song);
            }
            results.removeAllViews();
            if (tracks.isEmpty()) note(results, "暂无最近添加的歌曲。");
            else playlistTrackPage(results, TrackSort.ADDED.apply(new ArrayList<>(tracks.values()), true), 0);
            if (page.hasMore()) fill(controls, button("加载更多专辑中的歌曲", false,
                () -> loadRecentSongs(server, results, controls, tracks, offset + Navidrome.RECENT_ALBUMS_PER_PAGE)));
        });
    }

    private void libraryAlbums() {
        page("音乐库", "专辑");
        loadAlbums(body, 0);
    }

    private void libraryArtists() {
        page("音乐库", "艺术家");
        int id = generation;
        var results = column();
        fill(body, results);
        work(() -> sources.navidrome().artists(), (value, error) -> {
            if (!current(id)) return;
            if (error != null) { note(results, error); return; }
            List<JSONObject> artists = objects((JSONArray) value);
            if (artists.isEmpty()) note(results, "资料库里暂无艺术家。");
            renderArtists(results, artists, 0);
        });
    }

    private void renderArtists(LinearLayout target, List<JSONObject> artists, int start) {
        int end = Math.min(artists.size(), start + 50);
        for (JSONObject item : artists.subList(start, end)) {
            String name = item.optString("name");
            row(target, name, item.optInt("albumCount") + " 张专辑", libraryCover(item.optString("coverArt"), 192), () -> open(() -> {
                page("音乐库 / 艺术家", name);
                int id = generation;
                work(() -> sources.navidrome().artist(item.optString("id")), (value, error) -> {
                    if (!current(id)) return;
                    if (error != null) { note(body, error); return; }
                    List<JSONObject> albums = objects((JSONArray) value);
                    if (albums.isEmpty()) note(body, "这位艺术家暂无专辑。");
                    for (JSONObject album : albums) libraryAlbumRow(body, album);
                });
            }));
        }
        if (end < artists.size()) more(target, () -> renderArtists(target, artists, end));
    }

    private void libraryFavorites() {
        page("音乐库", "我喜欢的");
        int id = generation;
        work(() -> sources.navidrome().favorites(), (value, error) -> {
            if (!current(id)) return;
            if (error != null) { note(body, error); return; }
            var tracks = objects((JSONArray) value).stream().map(MediaEntry::library).collect(java.util.stream.Collectors.toList());
            playlistTracks(body, tracks, "favorites");
        });
    }

    private void libraryAlbumRow(LinearLayout target, JSONObject album) {
        String albumId = album.optString("id"), title = MediaEntry.first(album, "name", "title");
        row(target, title, album.optString("artist"), libraryCover(album.optString("coverArt"), 192), () -> open(() -> {
            page("音乐库 / 专辑", title);
            librarySongs(body, -1, unused -> sources.navidrome().album(albumId));
        }));
    }
    private void loadAlbums(LinearLayout target, int offset) {
        int id = generation;
        work(() -> sources.navidrome().albums(offset), (value, error) -> {
            if (!current(id)) return;
            if (error != null) { note(target, error); return; }
            var albums = objects((JSONArray) value);
            for (JSONObject album : albums) libraryAlbumRow(target, album);
            if (albums.isEmpty()) note(target, "资料库里暂无专辑。");
            if (albums.size() == 50) more(target, () -> loadAlbums(target, offset + 50));
        });
    }
    private void localLists() {
        page("音乐库", "本地歌单");
        if (prefs.getString("nav-url", "").trim().isEmpty()) {
            fill(body, button("添加服务器", true, () -> open(this::addLibraryServer)));
            return;
        }
        loadLibraryPlaylists();
    }

    private void loadLibraryPlaylists() {
        playlistScreen = true;
        gap(body, 8);
        var section = column();
        fill(body, section);
        loadLibraryPlaylists(section);
    }

    private void loadLibraryPlaylists(LinearLayout section) {
        section.removeAllViews();
        Navidrome server = sources.navidrome();
        String account = sources.navCacheKey();
        var tabs = line();
        var results = column();
        fill(section, tabs);
        gap(section, 8);
        fill(section, results);
        int id = generation;
        var create = button("创建歌单", false, () -> createLibraryPlaylist(server, account, "", () -> loadLibraryPlaylists(section)));
        tabs.addView(create);
        work(() -> playlists.navLists(false), (value, error) -> {
            if (!current(id) || !account.equals(sources.navCacheKey()) || results.getParent() != section) return;
            if (error != null) { note(results, error); return; }
            List<JSONObject> entries = objects((JSONArray) value);
            var own = new ArrayList<JSONObject>();
            var shared = new ArrayList<JSONObject>();
            for (JSONObject item : entries) {
                if (server.ownsPlaylist(item)) own.add(item);
                else shared.add(item);
            }
            String orderKey = libraryPlaylistOrderKey(account);
            LibraryPlaylistOrder.apply(own, prefs.getString(orderKey, "[]"));
            TextView[] selectors = new TextView[2];
            java.util.function.IntConsumer display = selected -> {
                results.removeAllViews();
                for (int i = 0; i < selectors.length; i++) {
                    boolean active = i == selected;
                    selectors[i].setTextColor(active ? ON_ACCENT : INK);
                    selectors[i].setBackground(ripple(active ? ACCENT : SOFT, PILL_RADIUS, RIPPLE));
                    selectors[i].setSelected(active);
                }
                var visible = selected == 0 ? own : shared;
                if (visible.isEmpty()) note(results, selected == 0 ? "此账户暂无歌单。" : "暂无其他用户共享的歌单。");
                if (selected == 0 && !visible.isEmpty()) note(results, "长按歌单拖动排序，点右侧 ⋯ 编辑或删除。");
                var rows = column();
                fill(results, rows);
                var reorder = selected == 0 ? new PlaylistReorder(rows, pageScroll,
                    (from, to) -> own.add(to, own.remove(from.intValue())),
                    () -> prefs.edit().putString(orderKey, LibraryPlaylistOrder.encode(own)).apply()) : null;
                for (JSONObject item : visible) {
                    String name = item.optString("name");
                    var date = TrackSort.parseDate(MediaEntry.first(item, "changed", "created"));
                    String detail = item.optInt("songCount") + " 首歌曲";
                    if (date != null) detail += " · 更新于 " + java.text.DateFormat.getDateInstance().format(java.util.Date.from(date));
                    String cover = libraryCover(item.optString("coverArt"), 256);
                    var wrapper = column();
                    fill(rows, wrapper);
                    View menu = selected == 0 ? iconButton(UiIcon.Kind.ELLIPSIS, name + "的歌单操作", false,
                        () -> libraryPlaylistMenu(server, account, item, () -> loadLibraryPlaylists(section))) : null;
                    row(wrapper, name, detail, cover, UiIcon.Kind.LIST, false, menu, () -> open(() -> localPlaylist(item)));
                    if (reorder != null) reorder.bind(wrapper, wrapper.getChildAt(0));
                }
            };
            selectors[0] = button("我的歌单", true, () -> display.accept(0));
            selectors[1] = button("共享歌单", false, () -> display.accept(1));
            tabs.removeAllViews();
            tabs.addView(selectors[0]); space(tabs, 6); tabs.addView(selectors[1]); space(tabs, 6); tabs.addView(create);
            display.accept(0);
        });
    }

    private String libraryPlaylistOrderKey(String account) {
        // Password rotations must not reset this server user's custom order.
        int passwordSeparator = account.lastIndexOf('\n');
        String identity = passwordSeparator < 0 ? account : account.substring(0, passwordSeparator);
        return "nav-playlist-order:" + MediaCache.digest(identity);
    }

    private void libraryPlaylistMenu(Navidrome server, String account, JSONObject item, Runnable refresh) {
        if (!server.ownsPlaylist(item) || !account.equals(sources.navCacheKey())) return;
        dialog().setTitle(item.optString("name", "歌单"))
            .setItems(new String[]{"编辑歌单", "删除歌单"}, (dialog, selected) -> {
                if (selected == 0) editLibraryPlaylist(server, account, item, refresh);
                else deleteLibraryPlaylist(server, account, item, refresh);
            }).setNegativeButton("取消", null).show();
    }

    private void editLibraryPlaylist(Navidrome server, String account, JSONObject item, Runnable refresh) {
        int pageId = generation;
        var panel = column();
        panel.setPadding(dp(20), dp(10), dp(20), 0);
        EditText name = field("歌单名称", item.optString("name"), InputType.TYPE_CLASS_TEXT);
        EditText comment = field("歌单简介", item.optString("comment"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        var shared = settingSwitch("公开共享歌单", item.optBoolean("public"));
        fill(panel, name); gap(panel, 10); fill(panel, comment); gap(panel, 10); fill(panel, shared);
        var editor = dialog().setTitle("编辑歌单").setView(panel)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).show();
        var submit = editor.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(view -> {
            String title = name.getText().toString().trim(), description = comment.getText().toString();
            boolean isPublic = shared.isChecked();
            if (title.isEmpty()) { name.setError("请输入歌单名称"); return; }
            if (!account.equals(sources.navCacheKey())) { editor.dismiss(); return; }
            submit.setEnabled(false); name.setEnabled(false); comment.setEnabled(false); shared.setEnabled(false);
            work(() -> {
                server.editPlaylist(item.optString("id"), title, description, isPublic);
                playlists.navChanged(account, item.optString("id"));
                return null;
            }, (value, error) -> {
                if (error != null) {
                    submit.setEnabled(true); name.setEnabled(true); comment.setEnabled(true); shared.setEnabled(true);
                    if (editor.isShowing()) name.setError(error); else toast(error);
                    return;
                }
                editor.dismiss();
                toast("已保存歌单");
                if (current(pageId) && account.equals(sources.navCacheKey())) refresh.run();
            });
        });
    }

    private void deleteLibraryPlaylist(Navidrome server, String account, JSONObject item, Runnable refresh) {
        int pageId = generation;
        var confirmation = dialog().setTitle("删除歌单")
            .setMessage("确定删除「" + item.optString("name", "未命名歌单") + "」？歌单会从服务器删除，其中的歌曲文件会保留。")
            .setNegativeButton("取消", null).setPositiveButton("删除", null).show();
        var submit = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(view -> {
            if (!account.equals(sources.navCacheKey())) { confirmation.dismiss(); return; }
            submit.setEnabled(false);
            work(() -> {
                server.deletePlaylist(item.optString("id"));
                playlists.navChanged(account, item.optString("id"));
                return null;
            }, (value, error) -> {
                if (error != null) { submit.setEnabled(true); toast(error); return; }
                confirmation.dismiss();
                String orderKey = libraryPlaylistOrderKey(account);
                prefs.edit().putString(orderKey, LibraryPlaylistOrder.remove(prefs.getString(orderKey, "[]"), item.optString("id"))).apply();
                toast("已删除歌单");
                if (current(pageId) && account.equals(sources.navCacheKey())) refresh.run();
            });
        });
    }

    private void createLibraryPlaylist(Navidrome server, String account, String songId, Runnable refresh) {
        int id = generation;
        var panel = column();
        panel.setPadding(dp(20), dp(10), dp(20), 0);
        EditText name = field("歌单名称", "", InputType.TYPE_CLASS_TEXT);
        fill(panel, name);
        var dialog = dialog().setTitle(songId.isEmpty() ? "创建歌单" : "创建歌单并加入歌曲")
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("创建", null).show();
        var submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(view -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("请输入歌单名称"); return; }
            if (!account.equals(sources.navCacheKey())) { dialog.dismiss(); return; }
            submit.setEnabled(false);
            name.setEnabled(false);
            work(() -> {
                server.createPlaylist(title, songId);
                playlists.navChanged(account, "");
                return null;
            }, (value, error) -> {
                if (error != null) {
                    if (dialog.isShowing()) {
                        submit.setEnabled(true);
                        name.setEnabled(true);
                        name.setError(error);
                    } else toast(error);
                    return;
                }
                dialog.dismiss();
                toast(songId.isEmpty() ? "已创建歌单" : "已创建歌单并加入歌曲");
                if (current(id) && account.equals(sources.navCacheKey())) refresh.run();
            });
        });
    }

    private final HashSet<String> updatingPlaylistSongs = new HashSet<>();

    private void removeLibraryPlaylistSong(Navidrome server, String account, JSONObject playlist, JSONArray snapshot,
                                          int position, MediaEntry song, Runnable reload) {
        int pageId = generation;
        String playlistId = playlist.optString("id");
        if (playlistId.isEmpty() || !account.equals(sources.navCacheKey()) || !server.canEditPlaylistTracks(playlist)) return;
        String mutation = account + "\n" + playlistId + "\nremove";
        var confirmation = dialog().setTitle("移出歌单")
            .setMessage("将「" + song.title() + "」从「" + playlist.optString("name", "歌单") + "」移出？歌曲文件会保留。")
            .setNegativeButton("取消", null).setPositiveButton("移出", null).show();
        var submit = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(view -> {
            if (!current(pageId) || !account.equals(sources.navCacheKey())) { confirmation.dismiss(); return; }
            if (!updatingPlaylistSongs.add(mutation)) { toast("正在更新此歌单，请稍后重试"); return; }
            submit.setEnabled(false);
            work(() -> {
                server.removeFromPlaylist(playlistId, snapshot, position);
                playlists.navChanged(account, playlistId);
                return null;
            }, (value, error) -> {
                updatingPlaylistSongs.remove(mutation);
                confirmation.dismiss();
                toast(error == null ? "已移出歌单" : error);
                // Refresh permissions and contents after either success or a stale/denied request.
                if (current(pageId) && account.equals(sources.navCacheKey())) reload.run();
            });
        });
    }

    private void chooseLibraryPlaylist(MediaEntry song) {
        Navidrome server = sources.navidrome();
        String account = sources.navCacheKey();
        int id = generation;
        work(server::playlists, (value, error) -> {
            if (!current(id) || !account.equals(sources.navCacheKey())) return;
            if (error != null) { toast(error); return; }
            var own = objects((JSONArray) value).stream().filter(server::ownsPlaylist)
                .filter(item -> !item.optString("id").isEmpty()).collect(java.util.stream.Collectors.toList());
            var labels = new ArrayList<String>();
            labels.add("创建歌单并加入");
            for (JSONObject item : own) labels.add(item.optString("name", "未命名歌单"));
            dialog().setTitle("加入歌单").setItems(labels.toArray(new String[0]), (dialog, selected) -> {
                if (selected == 0) { createLibraryPlaylist(server, account, song.id(), () -> {}); return; }
                String playlistId = own.get(selected - 1).optString("id");
                String key = account + "\n" + playlistId + "\n" + song.id();
                if (!account.equals(sources.navCacheKey()) || !updatingPlaylistSongs.add(key)) return;
                work(() -> {
                    boolean added = server.addToPlaylist(playlistId, song.id());
                    playlists.navChanged(account, playlistId);
                    return added;
                }, (added, failure) -> {
                    updatingPlaylistSongs.remove(key);
                    toast(failure == null ? (Boolean) added ? "已加入歌单" : "这首歌已在歌单中" : failure);
                });
            }).setNegativeButton("取消", null).show();
        });
    }

    private void localPlaylist(JSONObject item) {
        page("音乐库", "歌单");
        playlistScreen = true;
        String key = item.optString("id"), account = sources.navCacheKey();
        Navidrome server = sources.navidrome();
        int id = generation;
        var target = column();
        boolean[] loading = {false};
        boolean[] reloadAfterLoad = {false};
        FrameLayout[] refreshButton = new FrameLayout[1];
        Runnable[] reload = new Runnable[1];
        java.util.function.Consumer<Boolean> load = force -> {
            if (loading[0]) return;
            loading[0] = true;
            refreshButton[0].setEnabled(false);
            refreshButton[0].setAlpha(0.4f);
            refreshButton[0].setContentDescription(force ? "正在刷新歌单" : "正在加载歌单");
            work(() -> {
                JSONObject metadata = playlists.navPlaylist(key, force);
                if (metadata.optBoolean("_legacyCache")) {
                    metadata.put("name", item.optString("name"));
                    metadata.put("coverArt", item.optString("coverArt"));
                }
                if (force) playlists.navLists(true);
                return MediaSources.args("tracks", array(metadata, "entry"), "metadata", metadata);
            }, (value, error) -> {
                if (!current(id) || !account.equals(sources.navCacheKey())) return;
                loading[0] = false;
                refreshButton[0].setEnabled(true);
                refreshButton[0].setAlpha(1f);
                refreshButton[0].setContentDescription("刷新歌单");
                if (reloadAfterLoad[0]) {
                    reloadAfterLoad[0] = false;
                    reload[0].run();
                    return;
                }
                if (error != null) {
                    if (target.getChildCount() == 0) {
                        playlistTracks(target, List.of(), key, refreshButton[0]);
                        note(target, error);
                    }
                    toast(error);
                    return;
                }
                var result = (JSONObject) value;
                JSONObject metadata = result.optJSONObject("metadata");
                target.removeAllViews();
                JSONArray snapshot = result.optJSONArray("tracks");
                var entries = objects(snapshot).stream().map(MediaEntry::library).collect(java.util.stream.Collectors.toList());
                var originalPositions = new java.util.IdentityHashMap<MediaEntry, Integer>();
                for (int i = 0; i < entries.size(); i++) originalPositions.put(entries.get(i), i);
                java.util.function.Consumer<MediaEntry> remove = server.canEditPlaylistTracks(metadata) ? song -> {
                    if (!current(id) || !account.equals(sources.navCacheKey())) return;
                    if (loading[0]) { toast("歌单正在刷新，请稍后重试"); return; }
                    Integer position = originalPositions.get(song);
                    if (position != null) removeLibraryPlaylistSong(server, account, metadata, snapshot, position, song, reload[0]);
                } : null;
                View cover = playlistArtwork(metadata, entries);
                var placement = new LinearLayout.LayoutParams(dp(176), dp(176));
                placement.gravity = Gravity.CENTER_HORIZONTAL;
                target.addView(cover, placement);
                gap(target, 13);
                var heading = text(metadata.optString("name"), 24, INK, true);
                heading.setGravity(Gravity.CENTER);
                heading.setAccessibilityHeading(true);
                fill(target, heading);
                int seconds = entries.stream().mapToInt(TrackSort::seconds).sum();
                var subtitle = text((seconds >= 3600 ? seconds / 3600 + " 小时 " : "") + (seconds / 60 % 60) + " 分钟 · " + entries.size() + " 首歌曲", 13, MUTED, false);
                subtitle.setGravity(Gravity.CENTER);
                gap(target, 5); fill(target, subtitle); gap(target, 14);
                playlistTracks(target, entries, key, refreshButton[0], remove);
                if (force) toast("歌单已刷新");
            });
        };
        reload[0] = () -> {
            if (loading[0]) reloadAfterLoad[0] = true;
            else load.accept(true);
        };
        refreshButton[0] = iconButton(UiIcon.Kind.REFRESH, "刷新歌单", false, 48, 20, false, reload[0]);
        fill(body, target);
        load.accept(false);
    }

    private View playlistArtwork(JSONObject playlist, List<MediaEntry> entries) {
        String address = libraryCover(playlist.optString("coverArt"), 512);
        var keys = new java.util.LinkedHashSet<String>();
        for (MediaEntry track : entries) {
            if (!track.cover().isEmpty()) keys.add(track.cover());
            if (keys.size() == 4) break;
        }
        if (!address.isEmpty()) return art(address, 176, false, R.drawable.discovery_grooves, bitmap -> {});
        if (keys.size() < 4) {
            String first = keys.isEmpty() ? "" : libraryCover(keys.iterator().next(), 512);
            return art(first, 176, false, R.drawable.discovery_grooves, bitmap -> {});
        }
        var mosaic = column();
        mosaic.setBackground(shape(SOFT, CARD_RADIUS));
        mosaic.setClipToOutline(true);
        var covers = keys.iterator();
        for (int y = 0; y < 2; y++) {
            var pair = line();
            for (int x = 0; x < 2; x++) {
                var cover = art(libraryCover(covers.next(), 256), 88, false, R.drawable.discovery_grooves, bitmap -> {});
                cover.setClipToOutline(false);
                pair.addView(cover, new LinearLayout.LayoutParams(0, -1, 1));
            }
            mosaic.addView(pair, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        return mosaic;
    }

    private void playlistTracks(LinearLayout target, List<MediaEntry> entries, String playlistId) {
        playlistTracks(target, entries, playlistId, null);
    }

    private void playlistTracks(LinearLayout target, List<MediaEntry> entries, String playlistId, FrameLayout refreshButton) {
        playlistTracks(target, entries, playlistId, refreshButton, null);
    }

    private void playlistTracks(LinearLayout target, List<MediaEntry> entries, String playlistId, FrameLayout refreshButton,
                                java.util.function.Consumer<MediaEntry> removeFromPlaylist) {
        if (refreshButton != null && refreshButton.getParent() instanceof android.view.ViewGroup parent) parent.removeView(refreshButton);
        if (entries.isEmpty()) {
            if (refreshButton != null) {
                var sorting = line();
                weight(sorting, text("歌单原顺序", 13, MUTED, true));
                sorting.addView(refreshButton);
                var sortButton = iconButton(UiIcon.Kind.SORT, "排列歌曲", false, 48, 20, false, () -> {});
                sortButton.setEnabled(false);
                sortButton.setAlpha(0.4f);
                sorting.addView(sortButton);
                fill(target, sorting);
            }
            note(target, "暂无歌曲。");
            return;
        }
        String key = "playlist-order:" + MediaCache.digest(sources.navCacheKey() + "\n" + playlistId);
        TrackSort[] fields = TrackSort.values();
        int[] field = {Math.max(0, Math.min(fields.length - 1, prefs.getInt(key, 0)))};
        boolean[] descending = {prefs.getBoolean(key + ":descending", true)};
        boolean hasPlaylistDate = entries.stream().anyMatch(entry -> TrackSort.addedToPlaylist(entry) != null);
        boolean hasDate = entries.stream().anyMatch(entry -> TrackSort.date(entry) != null);
        String dateLabel = hasPlaylistDate ? "加入歌单日期" : "加入资料库日期";
        var actions = line();
        var sorting = line();
        var results = column();
        weight(actions, button("顺序播放", true, () -> playPlaylist(fields[field[0]].apply(entries, descending[0]), false)));
        space(actions, 8);
        weight(actions, button("随机播放", false, () -> playPlaylist(fields[field[0]].apply(entries, descending[0]), true)));
        fill(target, actions);
        gap(target, 8);
        var label = text("", 13, MUTED, true);
        weight(sorting, label);
        Runnable render = () -> {
            results.removeAllViews();
            TrackSort selection = fields[field[0]];
            label.setText((selection == TrackSort.ADDED ? dateLabel : selection.label)
                + (selection == TrackSort.ORIGINAL ? "" : descending[0] ? " ↓" : " ↑"));
            if (selection == TrackSort.ADDED && hasPlaylistDate) note(results, "缺少加歌时间时使用入库日期，未知日期排在最后。");
            playlistTrackPage(results, selection.apply(entries, descending[0]), 0, removeFromPlaylist);
        };
        if (refreshButton != null) sorting.addView(refreshButton);
        sorting.addView(iconButton(UiIcon.Kind.SORT, "排列歌曲", false, 48, 20, false, () -> {
            var choices = java.util.Arrays.stream(fields).filter(option -> hasDate || option != TrackSort.ADDED).collect(java.util.stream.Collectors.toList());
            String[] options = choices.stream().map(option -> option == TrackSort.ADDED ? dateLabel : option.label).toArray(String[]::new);
            dialog().setTitle("排列歌曲").setSingleChoiceItems(options, choices.indexOf(fields[field[0]]), (dialog, index) -> {
                dialog.dismiss();
                field[0] = choices.get(index).ordinal();
                prefs.edit().putInt(key, field[0]).apply();
                render.run();
            }).setNeutralButton(descending[0] ? "改为升序" : "改为降序", (dialog, which) -> {
                descending[0] = !descending[0];
                prefs.edit().putBoolean(key + ":descending", descending[0]).apply();
                render.run();
            }).setNegativeButton("取消", null).show();
        }));
        fill(target, sorting);
        fill(target, results);
        if (!hasDate && fields[field[0]] == TrackSort.ADDED) field[0] = 0;
        render.run();
    }

    private void playlistTrackPage(LinearLayout target, List<MediaEntry> entries, int from) {
        playlistTrackPage(target, entries, from, null);
    }

    private void playlistTrackPage(LinearLayout target, List<MediaEntry> entries, int from,
                                   java.util.function.Consumer<MediaEntry> removeFromPlaylist) {
        int end = Math.min(entries.size(), from + 50);
        songs(target, entries, false, "", "library-playlist", from, end, false, removeFromPlaylist);
        if (end < entries.size()) more(target, () -> playlistTrackPage(target, entries, end, removeFromPlaylist));
    }

    private void playPlaylist(List<MediaEntry> entries, boolean shuffle) {
        PlaybackService.queue(this, entries, shuffle ? java.util.concurrent.ThreadLocalRandom.current().nextInt(entries.size()) : 0,
            false, shuffle ? PlayOrder.Mode.SHUFFLE : PlayOrder.Mode.SEQUENTIAL);
    }
    private interface LibraryPage { JSONArray load(int offset) throws Exception; }
    private void librarySongs(LinearLayout target, int offset, LibraryPage loader) {
        int id = generation;
        if (prefs.getString("nav-url", "").trim().isEmpty()) {
            fill(target, button("添加服务器", true, () -> open(this::addLibraryServer)));
            return;
        }
        work(() -> loader.load(offset), (value, error) -> {
            if (!current(id)) return;
            if (error != null) { note(target, error); return; }
            var entries = objects((JSONArray) value).stream().map(MediaEntry::library).collect(java.util.stream.Collectors.toList());
            songs(target, entries, false, "", "");
            if (offset >= 0 && entries.size() == 50) more(target, () -> librarySongs(target, offset + 50, loader));
        });
    }

    private void musicSettings() {
        page("音乐", "音乐设置");
        section("音源更新");
        row(body, "音源更新", "自动更新、检查更新与更新地址", "", () -> open(this::sourceSettings));
        updateLabel = text("", 14, ACCENT, true);
        fill(body, updateLabel);
        showUpdateCount();
        section("歌单更新");
        String mode = playlists.mode();
        int selected = Math.max(0, java.util.Arrays.asList(PlaylistRefresh.MODES).indexOf(mode));
        row(body, "自动更新歌单", PlaylistRefresh.LABELS[selected], "", () -> dialog()
            .setTitle("自动更新歌单").setSingleChoiceItems(PlaylistRefresh.LABELS, selected, (dialog, index) -> {
                playlists.setMode(PlaylistRefresh.MODES[index]);
                playlists.foreground(null);
                dialog.dismiss();
                refresh();
            }).setNegativeButton("取消", null).show());
        var unmetered = settingSwitch("仅在不计费网络自动更新", playlists.unmeteredOnly());
        unmetered.setOnCheckedChangeListener((view, checked) -> playlists.setUnmeteredOnly(checked));
        fill(body, unmetered);
        int id = generation;
        fill(body, button("立即检查所有歌单并更新", true, () -> {
            toast("正在检查歌单…");
            playlists.refresh(report -> {
                if (!current(id)) return;
                toast("已更新 " + report.updated() + " 项" + (report.failed() > 0 ? "，" + report.failed() + " 项暂不可用" : ""));
                refresh();
            });
        }));
        long last = prefs.getLong("playlist-refresh-at", 0);
        note(body, "自动更新关注的歌单、默认在线歌单和 Navidrome 歌单。手动检查也会更新已浏览的歌单。"
            + (last == 0 ? "" : "\n最近检查：" + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(last))));
        section("音乐音源");
        importButtons(false);
        section("播放");
        row(body, "默认播放音质", qualityLabel(preferredQuality()), "", () -> chooseQuality(() -> {
            PlaybackService player = PlaybackService.active;
            if (player != null) player.reloadQuality();
            refresh();
        }));
        section("已导入音源");
        List<JSONObject> items = objects(sources.registered("music-sources"));
        if (items.isEmpty()) note(body, "从文件或网址导入可信的 LX JavaScript 音源。");
        for (JSONObject item : items) {
            String name = item.optString("name");
            row(body, name, item.optBoolean("enabled") ? "已启用" : "已停用", "", () -> dialog()
                .setTitle(name).setItems(new String[]{item.optBoolean("enabled") ? "停用" : "启用", "删除"}, (dialog, index) -> {
                    var list = new JSONArray();
                    for (JSONObject entry : items) {
                        if (!entry.optString("id").equals(item.optString("id"))) list.put(entry);
                        else if (index == 0) {
                            try { entry.put("enabled", !entry.optBoolean("enabled")); } catch (Exception ignored) {}
                            list.put(entry);
                        } else new java.io.File(getFilesDir(), "source-" + item.optString("id") + ".js").delete();
                    }
                    prefs.edit().putString("music-sources", list.toString()).apply(); refresh();
                }).show());
        }
    }
    private void importButtons(boolean books) {
        var controls = line();
        controls.addView(button("从文件导入", true, () -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, books ? IMPORT_BOOKS : IMPORT_MUSIC);
        }));
        space(controls, 8);
        controls.addView(button("从网址导入", false, () -> prompt("导入源", books ? "ZIP 插件地址" : "JavaScript 音源地址", "", value -> {
            if (!value.trim().isEmpty()) importUrl(value, books);
        })));
        fill(body, controls);
        gap(body, 9);
    }
    private void importUrl(String address, boolean books) {
        toast("正在读取音源…");
        work(() -> SourceUpdates.fetch(address), (value, error) -> {
            if (error != null) toast(error);
            else importSource((byte[]) value, books, address);
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || (request != IMPORT_MUSIC && request != IMPORT_BOOKS)) return;
        Uri uri = data.getData();
        work(() -> {
            try (var stream = getContentResolver().openInputStream(uri)) { return Http.bytes(stream, 4_000_000); }
        }, (value, error) -> {
            if (error != null) toast(error); else importSource((byte[]) value, request == IMPORT_BOOKS, "");
        });
    }
    private void importSource(byte[] data, boolean books, String address) {
        int id = generation;
        ScriptRuntime.Callback done = (value, error) -> {
            if (error != null) toast(error);
            else {
                toast(books ? "已导入 " + value + " 个听书源" : "已导入 " + value);
                if (current(id)) refresh();
            }
        };
        try {
            if (books) sources.importBooks(PluginArchive.read(data), address, "", done);
            else sources.importMusic(new String(data, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", ""), address, "", done);
        } catch (Exception error) { toast(error.getMessage()); }
    }
    private void bookPlugins() {
        page("有声书", "插件");
        importButtons(true);
        fill(body, button("导入 / 更新公开源合集 5.7", false, () -> importUrl(MediaSources.SSTS_SOURCES, true)));
        List<JSONObject> entries = sources.books(false);
        section("已安装的源 · " + entries.size());
        if (entries.isEmpty()) note(body, "导入插件后可按源发现和搜索。");
        for (JSONObject source : entries) {
            String id = source.optString("id"), name = source.optString("name");
            boolean enabled = prefs.getBoolean("book-enable-" + id, source.optBoolean("enabled", true));
            row(body, name, enabled ? "已启用" : "已停用", "", () -> {
                var menu = new ArrayList<String>();
                var actions = new ArrayList<Runnable>();
                if (enabled && source.optBoolean("discover")) {
                    menu.add("浏览分类");
                    actions.add(() -> open(() -> menus(source)));
                }
                if (enabled && source.optBoolean("search")) {
                    menu.add("在该源搜索");
                    actions.add(() -> { searchBookSource = id; open(this::bookSearch); });
                }
                menu.add(enabled ? "停用" : "启用");
                actions.add(() -> { prefs.edit().putBoolean("book-enable-" + id, !enabled).apply(); refresh(); });
                String login = source.optString("loginUrl");
                if (!login.trim().isEmpty()) { menu.add("登录"); actions.add(() -> open(() -> website(name + "登录", login))); }
                JSONArray config = array(source, "config");
                if (config.length() > 0) { menu.add("源设置"); actions.add(() -> open(() -> pluginSettings(source))); }
                dialog().setTitle(name).setItems(menu.toArray(new String[0]),
                    (dialog, which) -> actions.get(which).run()).show();
            });
        }
        for (JSONObject group : objects(sources.registered("book-plugins"))) {
            row(body, "移除 " + group.optString("name"), group.optString("version"), "", () ->
                dialog().setTitle("移除插件包？").setNegativeButton("取消", null).setPositiveButton("移除", (dialog, index) -> {
                    var list = new JSONArray();
                    for (JSONObject other : objects(sources.registered("book-plugins"))) {
                        if (!other.optString("id").equals(group.optString("id"))) list.put(other);
                    }
                    prefs.edit().putString("book-plugins", list.toString()).apply();
                    new java.io.File(getFilesDir(), "source-" + group.optString("id") + ".js").delete();
                    refresh();
                }).show());
        }
    }
    private void pluginSettings(JSONObject source) {
        String id = source.optString("id");
        page("听书源设置", source.optString("name"));
        var config = objects(array(source, "config"));
        var values = new java.util.LinkedHashMap<String, java.util.function.Supplier<String>>();
        var form = settingsCard(body);
        for (JSONObject item : config) {
            String key = MediaEntry.first(item, "key", "name");
            if (key.trim().isEmpty()) continue;
            String label = MediaEntry.first(item, "title", "label", "name", "key");
            String current = prefs.getString("book-pref-" + id + "-" + key, MediaEntry.first(item, "default", "defaultValue"));
            String kind = item.optString("type");
            JSONArray choices = array(item, "options");
            if (kind.equals("switch")) {
                var toggle = settingSwitch(label, "true".equalsIgnoreCase(current) || "1".equals(current));
                fill(form, toggle);
                values.put(key, () -> Boolean.toString(toggle.isChecked()));
            } else if (kind.equals("select") && choices.length() > 0) {
                String[] options = new String[choices.length()];
                String[] optionValues = new String[choices.length()];
                for (int i = 0; i < choices.length(); i++) {
                    JSONObject option = choices.optJSONObject(i);
                    options[i] = option == null ? choices.optString(i) : MediaEntry.first(option, "label", "name", "value");
                    optionValues[i] = option == null ? options[i] : option.optString("value", options[i]);
                }
                var index = new int[]{Math.max(0, java.util.Arrays.asList(optionValues).indexOf(current))};
                fill(form, text(label, 13, MUTED, true));
                gap(form, 6);
                TextView[] selector = new TextView[1];
                selector[0] = button(options[index[0]] + " ▾", false, () -> dialog()
                    .setTitle(label).setSingleChoiceItems(options, index[0], (dialog, selection) -> {
                        index[0] = selection;
                        selector[0].setText(options[selection] + " ▾");
                        dialog.dismiss();
                    }).show());
                fill(form, selector[0]);
                values.put(key, () -> optionValues[index[0]]);
            } else {
                int input = "password".equals(kind) ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : "number".equals(kind) ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT;
                var entry = labeledField(form, label, "", current, input);
                values.put(key, () -> entry.getText().toString());
            }
            gap(form, 12);
        }
        fill(form, button("保存源设置", true, () -> {
            var outgoing = new JSONObject();
            var edit = prefs.edit();
            for (var item : values.entrySet()) {
                String value = item.getValue().get();
                edit.putString("book-pref-" + id + "-" + item.getKey(), value);
                try { outgoing.put(item.getKey(), value); } catch (Exception ignored) {}
            }
            sources.book(id, "setPrefs", new JSONArray().put(outgoing), (value, error) -> {
                if (error == null) { edit.apply(); toast("已保存"); } else toast(error);
            });
        }));
    }
    private void website(String title, String address) {
        page("听书源", title);
        try { Http.url(address); } catch (Exception error) { note(body, error.getMessage()); return; }
        var web = new WebView(this);
        browser = web;
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);
            }
        });
        fill(body, button("完成并返回", true, () -> {
            android.webkit.CookieManager.getInstance().flush();
            onBackPressed();
        }));
        gap(body, 8);
        body.addView(web, new LinearLayout.LayoutParams(-1, dp(540)));
        web.loadUrl(address);
    }
    private void closeBrowser() {
        if (browser != null) {
            if (browser.getParent() instanceof android.view.ViewGroup group) group.removeView(browser);
            browser.destroy();
            browser = null;
        }
    }
    private void bookSettings() {
        page("有声书", "听书设置");
        row(body, "听书源与插件", "导入、登录和设置", "", () -> open(this::bookPlugins));
        row(body, "公开源合集", "随身听书 5.7 · 更新听书源", "", UiIcon.Kind.REFRESH,
            () -> importUrl(MediaSources.SSTS_SOURCES, true));
    }
    private String bookQuery = "";
    private String searchBookSource = "";
    private void bookSearch() {
        page("有声书", "搜索");
        var enabled = sources.books(true).stream().filter(source -> source.optBoolean("search")).collect(java.util.stream.Collectors.toList());
        if (enabled.isEmpty()) {
            note(body, "没有启用支持搜索的听书源。");
            fill(body, button("打开插件管理", true, () -> open(this::bookPlugins)));
            return;
        }
        JSONObject selected = enabled.stream().filter(source -> searchBookSource.equals(source.optString("id"))).findFirst().orElse(null);
        fill(body, button((selected == null ? "全部听书源" : selected.optString("name")) + " ▾", false, () -> {
            var names = new ArrayList<String>();
            names.add("全部听书源");
            for (JSONObject source : enabled) names.add(source.optString("name"));
            dialog().setTitle("搜索来源").setItems(names.toArray(new String[0]), (dialog, index) -> {
                searchBookSource = index == 0 ? "" : enabled.get(index - 1).optString("id");
                refresh();
            }).show();
        }));
        gap(body, 10);
        searchBar("搜索书名或作者", bookQuery, value -> { bookQuery = value; refresh(); });
        if (bookQuery.trim().isEmpty()) return;
        if (selected != null) searchBooks(body, selected, bookQuery, 1, null);
        else {
            int pageId = generation;
            var queue = new ArrayDeque<>(enabled);
            String query = bookQuery;
            Runnable[] next = new Runnable[1];
            next[0] = () -> {
                if (!current(pageId) || queue.isEmpty()) return;
                JSONObject source = queue.remove();
                searchBooks(body, source, query, 1, next[0]);
            };
            for (int i = 0; i < Math.min(3, enabled.size()); i++) next[0].run();
        }
    }
    private void searchBooks(LinearLayout target, JSONObject source, String query, int page, Runnable finished) {
        int id = generation;
        String sourceId = source.optString("id"), name = source.optString("name");
        var results = column();
        fill(target, results);
        sources.book(sourceId, "search", new JSONArray().put(query).put(page), (value, error) -> {
            if (!current(id)) return;
            if (error != null) {
                row(results, name, error, "", () -> sources.book(sourceId, "searchVerificationUrl", new JSONArray().put(query), (link, problem) -> {
                    if (!current(id)) return;
                    if (problem == null && link instanceof String address) open(() -> website(name + "验证", address)); else toast(error);
                }));
            } else {
                JSONObject result = value instanceof JSONObject object ? object : new JSONObject();
                List<JSONObject> books = objects(array(result, "books"));
                bookRows(results, sourceId, books);
                if (books.isEmpty() && finished == null) note(results, "没有找到匹配的书。");
                if (result.optInt("totalPage", 1) > page) more(results, () -> searchBooks(results, source, query, page + 1, null));
            }
            if (finished != null) finished.run();
        });
    }
    private void bookRows(LinearLayout target, String source, List<JSONObject> books) {
        for (JSONObject book : books) {
            String sourceId = source.isEmpty() ? book.optString("_source") : source;
            var card = line();
            card.setPadding(dp(10), dp(10), dp(13), dp(10));
            card.addView(bookCover(book, 64), new LinearLayout.LayoutParams(dp(64), dp(84)));
            space(card, 13);
            var labels = column();
            var title = text(MediaEntry.first(book, "title", "name"), 16, INK, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, title);
            String author = MediaEntry.first(book, "author", "artist");
            if (!author.isBlank()) {
                gap(labels, 6);
                var byline = text(author, 13, MUTED, false);
                byline.setSingleLine();
                byline.setEllipsize(TextUtils.TruncateAt.END);
                fill(labels, byline);
            }
            gap(labels, 7);
            JSONObject registration = sources.bookSource(sourceId);
            String sourceName = registration == null ? "听书源" : registration.optString("name");
            String status = book.optString("status");
            var detail = text(sourceName + (status.isBlank() ? "" : " · " + status), 11, MUTED, false);
            detail.setMaxLines(1);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            fill(labels, detail);
            weight(card, labels);
            clickable(card, PAPER, 16, () -> open(() -> bookDetail(sourceId, book)));
            fill(target, card);
            gap(target, 9);
        }
    }

    private FrameLayout bookCover(JSONObject book, int width) {
        var cover = new FrameLayout(this);
        cover.setBackground(shape(darkAppearance ? 0xff293e42 : 0xffe5ebe3, 10));
        cover.setClipToOutline(true);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        var placeholder = text(MediaEntry.first(book, "title", "name"), Math.max(13, width / 6),
            darkAppearance ? 0xffb9cfbf : 0xff566951, true);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setMaxLines(3);
        placeholder.setEllipsize(TextUtils.TruncateAt.END);
        placeholder.setPadding(dp(8), dp(10), dp(8), dp(10));
        cover.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        String address = book.optString("coverUrl");
        if (!address.isBlank()) {
            var image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            Runnable load = () -> loadCover(image, address, CoverThumbnail.edge(dp(width)), true, true, ignored -> {});
            if (coverViewport != null) coverViewport.loadWhenVisible(image, load);
            else load.run();
        }
        return cover;
    }

    private void bookGrid(LinearLayout target, List<JSONObject> books, String source) {
        int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 4
            : getResources().getConfiguration().fontScale > 1.3f ? 2 : 3;
        int width = (getResources().getConfiguration().screenWidthDp - 32 - (columns - 1) * 12) / columns;
        for (int start = 0; start < books.size(); start += columns) {
            var row = line();
            row.setGravity(Gravity.TOP);
            for (int col = 0; col < columns; col++) {
                if (col > 0) space(row, 12);
                if (start + col >= books.size()) { row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); continue; }
                JSONObject book = books.get(start + col);
                String sourceId = source.isEmpty() ? book.optString("_source") : source;
                var card = column();
                card.addView(bookCover(book, width), new LinearLayout.LayoutParams(-1, dp(Math.round(width * 1.3f))));
                gap(card, 8);
                String name = MediaEntry.first(book, "title", "name");
                var title = text(name, 14, INK, true);
                title.setMaxLines(2);
                title.setMinLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                fill(card, title);
                gap(card, 3);
                String byline = MediaEntry.first(book, "author", "artist", "status");
                var author = text(byline.isBlank() ? "点击收听" : byline, 11, MUTED, false);
                author.setSingleLine();
                author.setEllipsize(TextUtils.TruncateAt.END);
                fill(card, author);
                card.setContentDescription(name + (byline.isBlank() ? "" : "，" + byline));
                card.setScreenReaderFocusable(true);
                clickable(card, Color.TRANSPARENT, 10, () -> open(() -> bookDetail(sourceId, book)));
                row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
            }
            fill(target, row);
            gap(target, 18);
        }
    }
    private void discover() {
        page("有声书", "发现");
        List<JSONObject> enabled = discoveryBookSources();
        if (enabled.isEmpty()) {
            note(body, "启用听书源后，可以在这里浏览书籍、广播与播客。");
            fill(body, button("管理听书源", true, () -> open(this::bookPlugins)));
            return;
        }
        String preferred = bookDiscoverySource.isEmpty() ? prefs.getString("book-discovery-source", "") : bookDiscoverySource;
        JSONObject source = enabled.stream().filter(item -> preferred.equals(item.optString("id"))).findFirst().orElse(enabled.get(0));
        bookDiscoverySource = source.optString("id");
        prefs.edit().putString("book-discovery-source", bookDiscoverySource).apply();
        String signature = sources.bookDiscoverySignature(source);
        BookDiscovery state = bookDiscoveries.get(bookDiscoverySource);
        if (state == null || !signature.equals(state.signature)) {
            state = new BookDiscovery(bookDiscoverySource, signature);
            bookDiscoveries.put(bookDiscoverySource, state);
        }
        activeBookDiscovery = state;
        bookDiscoveries.trim(state);
        var toolbar = line();
        var selector = line();
        selector.setPadding(dp(14), dp(9), dp(12), dp(9));
        selector.addView(new UiIcon(this, UiIcon.Kind.PLUGIN, 19, ACCENT), new LinearLayout.LayoutParams(dp(24), dp(28)));
        space(selector, 7);
        var sourceLabel = text(source.optString("name"), 16, INK, true);
        sourceLabel.setSingleLine();
        sourceLabel.setEllipsize(TextUtils.TruncateAt.END);
        weight(selector, sourceLabel);
        selector.addView(new UiIcon(this, UiIcon.Kind.DOWN, 15, MUTED), new LinearLayout.LayoutParams(dp(23), dp(26)));
        clickable(selector, PAPER, 18, this::chooseBookDiscoverySource);
        selector.setContentDescription("切换听书源，当前为" + source.optString("name"));
        weight(toolbar, selector);
        space(toolbar, 6);
        toolbar.addView(iconButton(UiIcon.Kind.REFRESH, "刷新当前分类", false, 44, 20, false, () -> reloadBookDiscovery(false)));
        boolean grid = prefs.getBoolean("book-discovery-grid", true);
        toolbar.addView(iconButton(grid ? UiIcon.Kind.LIST : UiIcon.Kind.ALBUM, grid ? "切换列表显示" : "切换封面网格", false,
            44, 20, false, () -> {
                prefs.edit().putBoolean("book-discovery-grid", !prefs.getBoolean("book-discovery-grid", true)).apply();
                refresh();
            }));
        fill(body, toolbar);
        gap(body, 12);
        bookDiscoveryFilters = column();
        bookDiscoveryResults = column();
        bookDiscoveryFooter = column();
        fill(body, bookDiscoveryFilters);
        fill(body, bookDiscoveryResults);
        fill(body, bookDiscoveryFooter);
        if (state.menusLoaded) renderBookDiscovery();
        else loadBookDiscoveryMenus(source, state);
    }

    private List<JSONObject> discoveryBookSources() {
        return sources.books(true).stream().filter(source -> source.optBoolean("discover")).collect(Collectors.toList());
    }

    private void menus(JSONObject source) {
        bookDiscoverySource = source.optString("id");
        screen = this::discover;
        discover();
    }

    private void chooseBookDiscoverySource() {
        var picker = new android.app.Dialog(this);
        var sheet = column();
        sheet.setPadding(dp(18), dp(18), dp(18), dp(20));
        var title = line();
        weight(title, text("切换听书源", 22, INK, true));
        title.addView(iconButton(UiIcon.Kind.CLOSE, "关闭", false, 44, 20, false, picker::dismiss));
        fill(sheet, title);
        var scroll = new ScrollView(this);
        var choices = column();
        scroll.addView(choices);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        var groups = new LinkedHashMap<String, List<JSONObject>>();
        for (JSONObject source : discoveryBookSources()) {
            String group = source.optString("group", "听书");
            if (group.isBlank()) group = "听书";
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(source);
        }
        for (var group : groups.entrySet()) {
            gap(choices, 15);
            fill(choices, text(group.getKey(), 13, MUTED, true));
            gap(choices, 8);
            for (JSONObject source : group.getValue()) {
                boolean selected = bookDiscoverySource.equals(source.optString("id"));
                var choice = line();
                choice.setPadding(dp(12), dp(13), dp(12), dp(13));
                weight(choice, text(source.optString("name"), 16, selected ? ACCENT : INK, selected));
                if (selected) choice.addView(new UiIcon(this, UiIcon.Kind.PLAY, 13, ACCENT), new LinearLayout.LayoutParams(dp(20), dp(24)));
                clickable(choice, selected ? ACCENT_TINT : PAPER, 14, () -> {
                    picker.dismiss();
                    bookDiscoverySource = source.optString("id");
                    discover();
                });
                fill(choices, choice);
                gap(choices, 6);
            }
        }
        gap(sheet, 12);
        fill(sheet, button("管理听书源", false, () -> { picker.dismiss(); open(this::bookPlugins); }));
        picker.setContentView(sheet);
        showBottomSheet(picker, sheet, .76f);
    }

    private boolean currentBookDiscovery(int page, BookDiscovery state) {
        JSONObject source = sources.bookSource(state.source);
        return current(page) && activeBookDiscovery == state && source != null
            && prefs.getBoolean("book-enable-" + state.source, source.optBoolean("enabled", true))
            && state.signature.equals(sources.bookDiscoverySignature(source));
    }

    private void loadBookDiscoveryMenus(JSONObject source, BookDiscovery state) {
        int page = generation;
        bookDiscoveryLoading = true;
        note(bookDiscoveryFooter, "正在加载分类…");
        bookDiscoveryRequest = sources.bookDiscovery(state.source, state.signature, "categoryMenus", new JSONArray(), (value, error) -> {
            if (!currentBookDiscovery(page, state)) return;
            bookDiscoveryRequest = null;
            bookDiscoveryLoading = false;
            bookDiscoveryFooter.removeAllViews();
            if (error != null || !(value instanceof JSONArray rows)) {
                note(bookDiscoveryFooter, error == null ? "该源未返回有效分类。" : error);
                fill(bookDiscoveryFooter, button("重新加载分类", false, () -> reloadBookDiscovery(true)));
                return;
            }
            state.menus = BookDiscovery.menus(rows);
            state.menusLoaded = true;
            renderBookDiscovery();
        });
    }

    private TextView bookCategoryChip(String title, boolean selected, Runnable select) {
        var chip = text(title, 13, selected ? ON_ACCENT : INK, selected);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(15), dp(10), dp(15), dp(10));
        chip.setMinHeight(dp(44));
        chip.setSelected(selected);
        clickable(chip, selected ? ACCENT : PAPER, 15, select);
        return chip;
    }

    private void bookCategoryChips(LinearLayout target, LinearLayout strip) {
        chips(target, strip);
        for (int i = 0; i < strip.getChildCount(); i++) {
            View selected = strip.getChildAt(i);
            if (selected.isSelected()) {
                selected.post(() -> {
                    if (selected.isAttachedToWindow() && strip.getParent() instanceof android.widget.HorizontalScrollView scroll) {
                        scroll.scrollTo(Math.max(0, selected.getLeft() - dp(8)), 0);
                    }
                });
                break;
            }
        }
    }

    private void renderBookDiscovery() {
        BookDiscovery state = activeBookDiscovery;
        if (state == null) return;
        bookDiscoveryFilters.removeAllViews();
        bookDiscoveryResults.removeAllViews();
        bookDiscoveryFooter.removeAllViews();
        if (state.selected() == null) {
            note(bookDiscoveryResults, "该源暂未提供可浏览的分类，可以切换听书源。");
            fill(bookDiscoveryFooter, button("重新加载分类", false, () -> reloadBookDiscovery(true)));
            return;
        }
        if (state.menus.size() > 1) {
            var groups = line();
            for (int i = 0; i < state.menus.size(); i++) {
                int selected = i;
                groups.addView(bookCategoryChip(state.menus.get(i).title(), state.menu == i, () -> selectBookCategory(selected, 0)));
                space(groups, 7);
            }
            bookCategoryChips(bookDiscoveryFilters, groups);
            gap(bookDiscoveryFilters, 8);
        }
        var tabs = line();
        var menu = state.menus.get(state.menu);
        for (int i = 0; i < menu.tabs().size(); i++) {
            int selected = i;
            tabs.addView(bookCategoryChip(menu.tabs().get(i).title(), state.tab == i, () -> selectBookCategory(state.menu, selected)));
            space(tabs, 7);
        }
        bookCategoryChips(bookDiscoveryFilters, tabs);
        gap(bookDiscoveryFilters, 18);
        BookDiscovery.Page data = state.page();
        var heading = line();
        weight(heading, text(state.selected().title(), 20, INK, true));
        heading.addView(text(data.books.size() + " 项", 12, MUTED, false));
        fill(bookDiscoveryResults, heading);
        gap(bookDiscoveryResults, 12);
        var books = List.copyOf(data.books.values());
        if (prefs.getBoolean("book-discovery-grid", true)) bookGrid(bookDiscoveryResults, books, state.source);
        else bookRows(bookDiscoveryResults, state.source, books);
        if (data.loaded && books.isEmpty()) note(bookDiscoveryResults, "这个分类暂时没有内容，试试其他分类。");
        updateBookDiscoveryFooter();
        int page = generation;
        if (!data.loaded && data.error.isEmpty() && !bookDiscoveryLoading) loadBookDiscoveryPage();
        else if (state.scroll > 0 && pageScroll != null) {
            int position = state.scroll;
            pageScroll.post(() -> {
                if (currentBookDiscovery(page, state) && state.page() == data) pageScroll.scrollTo(0, position);
            });
        }
    }

    private void selectBookCategory(int menu, int tab) {
        BookDiscovery state = activeBookDiscovery;
        if (state == null || (state.menu == menu && state.tab == tab)) return;
        if (bookDiscoveryLoading) {
            // Rebuilding changes generation, so the previous request cannot repaint the new category.
            state.menu = menu;
            state.tab = tab;
            state.scroll = 0;
            activeBookDiscovery = null;
            discover();
            return;
        }
        state.menu = menu;
        state.tab = tab;
        state.scroll = 0;
        pageScroll.scrollTo(0, 0);
        renderBookDiscovery();
    }

    private void reloadBookDiscovery(boolean menus) {
        BookDiscovery state = activeBookDiscovery;
        if (state == null) return;
        if (menus || state.selected() == null) { state.menusLoaded = false; state.pages.clear(); }
        else state.pages.remove(state.selected().url());
        state.scroll = 0;
        activeBookDiscovery = null;
        discover();
    }

    private void loadBookDiscoveryPage() {
        BookDiscovery state = activeBookDiscovery;
        if (state == null || bookDiscoveryLoading) return;
        BookDiscovery.Page data = state.page();
        if (data == null || data.next.isBlank()) return;
        String cursor = data.next;
        int page = generation;
        bookDiscoveryLoading = true;
        data.error = "";
        updateBookDiscoveryFooter();
        bookDiscoveryRequest = sources.bookDiscovery(state.source, state.signature, "categoryPage", new JSONArray().put(cursor), (value, error) -> {
            if (!currentBookDiscovery(page, state) || state.page() != data) return;
            bookDiscoveryRequest = null;
            bookDiscoveryLoading = false;
            if (error != null || !(value instanceof JSONObject result)) {
                data.error = error == null ? "该源暂未返回内容。" : error;
                updateBookDiscoveryFooter();
                return;
            }
            state.scroll = pageScroll.getScrollY();
            data.accept(cursor, result);
            bookDiscoveries.trim(state);
            renderBookDiscovery();
        });
    }

    private void updateBookDiscoveryFooter() {
        if (activeBookDiscovery == null || bookDiscoveryFooter == null) return;
        var data = activeBookDiscovery.page();
        bookDiscoveryFooter.removeAllViews();
        if (data == null) return;
        if (bookDiscoveryLoading) note(bookDiscoveryFooter, data.loaded ? "正在加载下一页…" : "正在寻找好内容…");
        else if (!data.error.isEmpty()) {
            note(bookDiscoveryFooter, data.error);
            fill(bookDiscoveryFooter, button("重试", false, this::loadBookDiscoveryPage));
        } else if (!data.next.isEmpty()) {
            fill(bookDiscoveryFooter, button("加载更多", false, this::loadBookDiscoveryPage));
        } else if (data.loaded && !data.books.isEmpty()) note(bookDiscoveryFooter, "已显示全部内容");
    }

    private void maybeLoadBookDiscovery() {
        if (activeBookDiscovery == null || bookDiscoveryLoading || pageScroll == null || pageScroll.getChildCount() == 0) return;
        BookDiscovery.Page data = activeBookDiscovery.page();
        if (data == null || !data.loaded || !data.error.isEmpty() || data.next.isEmpty()) return;
        int remaining = pageScroll.getChildAt(0).getHeight() - pageScroll.getHeight() - pageScroll.getScrollY();
        if (remaining <= dp(240)) loadBookDiscoveryPage();
    }
    private void bookshelf() {
        page("有声书", "书架");
        for (String category : List.of("shelf", "recent-books")) {
            section(category.equals("shelf") ? "收藏" : "最近收听");
            List<JSONObject> books = objects(sources.registered(category));
            if (books.isEmpty()) note(body, category.equals("shelf") ? "收藏的书会显示在这里。" : "还没有播放记录。");
            for (JSONObject book : books) bookRows(body, book.optString("_source"), List.of(book));
        }
    }
    private boolean rememberBook(String category, String source, JSONObject book, boolean toggle) {
        var list = new JSONArray();
        boolean exists = false;
        for (JSONObject item : objects(sources.registered(category))) {
            if (source.equals(item.optString("_source")) && book.optString("bookUrl").equals(item.optString("bookUrl"))) exists = true;
            else if (list.length() < 149) list.put(item);
        }
        var updated = new JSONArray();
        boolean add = !exists || !toggle;
        if (add) {
            try { book.put("_source", source); updated.put(book); } catch (Exception ignored) {}
        }
        for (JSONObject item : objects(list)) updated.put(item);
        prefs.edit().putString(category, updated.toString()).apply();
        return add;
    }
    private void bookDetail(String source, JSONObject book) {
        String title = MediaEntry.first(book, "title", "name"), url = book.optString("bookUrl");
        page("有声书 / 章节", title);
        if (!book.optString("coverUrl").trim().isEmpty()) {
            var cover = art(book.optString("coverUrl"), 120);
            body.addView(cover, new LinearLayout.LayoutParams(dp(110), dp(144)));
            gap(body, 12);
        }
        fill(body, button("收藏 / 取消收藏", false, () -> toast(rememberBook("shelf", source, book, true) ? "已加入书架" : "已从书架移除")));
        gap(body, 12);
        if (url.trim().isEmpty()) { note(body, "该源未提供这本书的详情地址。"); return; }
        var intro = column();
        var status = column();
        var chapters = column();
        fill(body, intro);
        fill(body, status);
        fill(body, chapters);
        loadBookEpisodes(source, book, title, url, intro, status, chapters, false);
    }

    private List<MediaEntry> bookEntries(String source, JSONObject book, JSONObject detail) {
        var entries = new ArrayList<MediaEntry>();
        String url = book.optString("bookUrl"), title = MediaEntry.first(book, "title", "name");
        for (JSONObject episode : objects(array(detail, "episodes"))) {
            if (episode.optString("url").trim().isEmpty()) continue;
            try { episode.put("_book", url); } catch (Exception ignored) {}
            entries.add(MediaEntry.episode(episode, source, title, book.optString("coverUrl")));
        }
        return entries;
    }

    private int lastBookEpisode(String source, JSONObject book, List<MediaEntry> entries) {
        String last = prefs.getString("last-book-" + source + "-" + book.optString("bookUrl"), "");
        for (int i = 0; i < entries.size(); i++) if (last.equals(entries.get(i).id())) return i;
        return -1;
    }

    private void playBook(String source, JSONObject book, List<MediaEntry> entries, int offset) {
        rememberBook("recent-books", source, book, false);
        PlaybackService.queue(this, entries, Math.max(0, offset), true);
    }

    private boolean loadingHomeBook;

    private void resumeBook(String source, JSONObject book) {
        var player = PlaybackService.active;
        String url = book.optString("bookUrl");
        if (player != null && player.book && player.index() >= 0) {
            var entry = player.entries().get(player.index());
            if (source.equals(entry.source()) && url.equals(entry.raw().optString("_book"))) {
                player.resume();
                openPlayer();
                return;
            }
        }
        if (loadingHomeBook) return;
        loadingHomeBook = true;
        toast("正在加载上次的章节…");
        resumeBookEpisodes(source, book, url, generation, false);
    }

    private void resumeBookEpisodes(String source, JSONObject book, String url, int pageId, boolean full) {
        sources.book(source, "bookDetail", new JSONArray().put(url).put(true).put(full), (value, error) -> {
            if (!current(pageId)) { loadingHomeBook = false; return; }
            if (error != null || !(value instanceof JSONObject detail)) {
                loadingHomeBook = false;
                toast(error == null ? "该源暂未返回章节" : error);
                return;
            }
            var entries = bookEntries(source, book, detail);
            int index = lastBookEpisode(source, book, entries);
            if (index < 0 && !full && detail.optBoolean("_multipleEpisodePages")) {
                resumeBookEpisodes(source, book, url, pageId, true);
                return;
            }
            loadingHomeBook = false;
            if (entries.isEmpty()) { toast("这本书暂时没有可用章节"); return; }
            if (index < 0 && !prefs.getString("last-book-" + source + "-" + url, "").isEmpty()) {
                toast("找不到上次的章节，请选择章节");
                open(() -> bookDetail(source, book));
                return;
            }
            playBook(source, book, entries, index);
        });
    }
    private void loadBookEpisodes(String source, JSONObject book, String title, String url, LinearLayout intro,
                                  LinearLayout status, LinearLayout chapters, boolean full) {
        int id = generation;
        status.removeAllViews();
        note(status, full ? "正在加载全部章节…" : "正在获取章节…");
        sources.book(source, "bookDetail", new JSONArray().put(url).put(true).put(full), (value, error) -> {
            if (!current(id)) return;
            status.removeAllViews();
            if (error != null || !(value instanceof JSONObject detail)) {
                note(status, error == null ? "该源暂未返回章节。" : error);
                fill(status, button(full ? "重试加载全部" : "重试", false,
                    () -> loadBookEpisodes(source, book, title, url, intro, status, chapters, full)));
                return;
            }
            String description = MediaEntry.first(detail, "intro");
            if (!description.isEmpty() && intro.getChildCount() == 0) note(intro, description);
            List<MediaEntry> entries = bookEntries(source, book, detail);
            if (entries.isEmpty()) {
                note(status, full && chapters.getChildCount() > 0 ? "该源未返回完整章节，仍可访问已加载章节。" : "这本书暂时没有可用章节。");
            } else {
                chapters.removeAllViews();
                var actions = line();
                actions.addView(button("继续收听", true, () -> playBook(source, book, entries, lastBookEpisode(source, book, entries))));
                fill(chapters, actions); gap(chapters, 10);
                var shown = column();
                fill(chapters, shown);
                bookEpisodePage(shown, entries, title, 0, book);
            }
            if (!full && detail.optBoolean("_multipleEpisodePages")) {
                loadBookEpisodes(source, book, title, url, intro, status, chapters, true);
            }
        });
    }
    private void bookEpisodePage(LinearLayout target, List<MediaEntry> entries, String title, int start, JSONObject book) {
        int end = Math.min(start + 50, entries.size());
        for (int i = start; i < end; i++) {
            int index = i;
            MediaEntry entry = entries.get(i);
            var strip = line();
            strip.setPadding(dp(12), dp(8), dp(10), dp(8));
            TextView label = text((i + 1) + "  " + entry.title(), 15, INK, true);
            label.setPadding(0, dp(10), 0, dp(10));
            label.setOnClickListener(view -> {
                rememberBook("recent-books", entry.source(), book, false);
                PlaybackService.queue(this, entries, index, true);
            });
            weight(strip, label);
            strip.addView(new UiIcon(this, UiIcon.Kind.PLAY, 17, ACCENT), new LinearLayout.LayoutParams(dp(28), dp(36)));
            action(strip, () -> playBook(entry.source(), book, entries, index));
            strip.setBackground(shape(PAPER, CARD_RADIUS));
            fill(target, strip); gap(target, 6);
        }
        if (end < entries.size()) more(target, () -> bookEpisodePage(target, entries, title, end, book));
    }
    private void downloadChoice(List<MediaEntry> entries, boolean book, String collection, int first) {
        if (book || entries.isEmpty()) return;
        boolean remote = serverDownloadEnabled() && entries.stream().noneMatch(entry -> entry.source().equals("library"));
        String[] options = remote ? new String[]{"保存到手机", "推送到服务器"} : new String[]{"保存到手机"};
        String title = entries.size() == 1 ? entries.get(0).title() : "下载 " + entries.size() + " 集";
        dialog().setTitle(title).setItems(options,
            (dialog, index) -> chooseDownloadQuality(entries, book, collection, first, index == 1)).show();
    }
    private record DownloadRequest(List<MediaEntry> entries, boolean book, String collection, int first, boolean remote, String quality) {}

    private void chooseDownloadQuality(List<MediaEntry> entries, boolean book, String collection, int first, boolean remote) {
        if (book || entries.isEmpty()) return;
        if (entries.get(0).source().equals("library")) {
            startDownload(new DownloadRequest(entries, book, collection, first, remote, ""));
            return;
        }
        MediaEntry entry = entries.get(0);
        MusicQuality[] choices = MusicQuality.values();
        String[] labels = new String[choices.length];
        boolean[] supported = new boolean[choices.length];
        int selected = -1;
        for (int i = 0; i < choices.length; i++) {
            MusicQuality quality = choices[i];
            supported[i] = sources.canRequestQuality(entry, quality);
            String size = quality.size(entry);
            labels[i] = quality.label + (size.isEmpty() ? "" : "  ·  " + size) + "  ·  " + MediaSources.label(entry.source())
                + (supported[i] ? "" : "\n已启用的音源不支持此档");
            if (supported[i] && quality.key.equals(preferredQuality())) selected = i;
        }
        String title = remote ? "选择服务器下载音质" : "选择本机下载音质";
        dialog().setTitle(title).setSingleChoiceItems(labels, selected, (dialog, index) -> {
            if (!supported[index]) {
                if (dialog instanceof AlertDialog alert) alert.getListView().setItemChecked(index, false);
                toast("请启用支持该音质的音源");
                return;
            }
            dialog.dismiss();
            startDownload(new DownloadRequest(entries, false, collection, first, remote, choices[index].key));
        }).setNegativeButton("取消", null).show();
    }

    private void startDownload(DownloadRequest request) {
        if (request.book()) return;
        toast("正在获取下载地址…");
        submit(request, 0, 0, "");
    }

    private void submit(DownloadRequest request, int offset, int count, String lastError) {
        if (isDestroyed()) return;
        List<MediaEntry> entries = request.entries();
        boolean book = request.book(), remote = request.remote();
        String collection = request.collection();
        int first = request.first();
        if (offset == entries.size()) {
            String label = remote ? "已推送 " : "已加入本机下载 ";
            toast(label + count + " 项" + (count == entries.size() ? "" : "，失败 " + (entries.size() - count) + " 项 · " + lastError));
            return;
        }
        MediaEntry entry = entries.get(offset);
        sources.resolveDownload(entry, book, request.quality(), (media, error) -> {
            if (isDestroyed()) return;
            if (error != null) {
                main.post(() -> submit(request, offset + 1, count, error));
                return;
            }
            String name = book ? String.format(Locale.ROOT, "%04d - %s", first + offset, entry.title())
                : (entry.subtitle().trim().isEmpty() ? "" : entry.subtitle() + " - ") + entry.title();
            if (remote) {
                work(() -> Http.json(Http.base(prefs.getString("server-url", "")) + "/v1/jobs", serverHeaders(),
                    MediaSources.args("kind", book ? "audiobook" : "music", "title", name, "collection", collection,
                        "url", media.url(), "headers", media.jsonHeaders(), "artist", entry.subtitle(),
                        "album", MediaEntry.first(entry.raw(), "albumName", "album"), "trackTitle", entry.title(), "mediaType", media.hls() ? "hls" : "",
                        "quality", media.quality(), "source", entry.source(), "songId", book ? "" : entry.id(),
                        "track", book ? first + offset : 0)),
                    (value, failure) -> submit(request, offset + 1, count + (failure == null ? 1 : 0),
                        failure == null ? lastError : failure));
            } else {
                try {
                    var service = getSystemService(DownloadManager.class);
                    var download = new DownloadManager.Request(Uri.parse(media.url()));
                    for (String key : media.headers().keySet()) download.addRequestHeader(key, media.headers().getString(key));
                    download.setTitle(name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String suffix = Http.url(media.url()).getPath();
                    var pattern = java.util.regex.Pattern.compile("(?i)\\.([a-z0-9]{2,5})$").matcher(suffix);
                    String extension = !media.extension().isEmpty() ? media.extension()
                        : pattern.find() ? pattern.group(1).toLowerCase(Locale.ROOT) : "";
                    if (media.hls()) throw new IllegalArgumentException("HLS 音频可推送到服务器下载");
                    String verifiedMime = switch (media.extension()) {
                        case "flac", "aac", "wav", "ogg", "opus" -> "audio/" + media.extension();
                        case "mp3" -> "audio/mpeg";
                        case "m4a" -> "audio/mp4";
                        default -> "";
                    };
                    if (!verifiedMime.isEmpty()) download.setMimeType(verifiedMime);
                    if (extension.equals("m4s")) extension = "m4a";
                    var audioFormats = List.of("mp3", "m4a", "m4b", "flac", "aac", "wav", "ogg", "opus", "wma", "mp4", "webm", "eac3", "ac3", "mka", "aiff", "amr");
                    if (!audioFormats.contains(extension)) extension = entry.raw().optString("suffix", "").toLowerCase(Locale.ROOT);
                    if (!audioFormats.contains(extension)) {
                        extension = switch (media.quality()) {
                            case "flac", "flac24bit", "hires" -> "flac";
                            case "128k", "192k", "320k" -> "mp3";
                            default -> "";
                        };
                    }
                    String file = name.replaceAll("[^\\p{L}\\p{N} _.-]", "_").trim();
                    if (file.length() > 70) file = file.substring(0, 70);
                    download.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_MUSIC,
                        file + "-" + Long.toUnsignedString(System.nanoTime()) + (extension.isEmpty() ? "" : "." + extension));
                    long key = service.enqueue(download);
                    var saved = sources.registered("offline");
                    saved.put(MediaSources.args("id", key, "title", name, "book", book, "collection", collection, "quality", media.quality()));
                    prefs.edit().putString("offline", saved.toString()).apply();
                    main.post(() -> submit(request, offset + 1, count + 1, lastError));
                } catch (Exception failure) {
                    main.post(() -> submit(request, offset + 1, count, failure.getMessage()));
                }
            }
        });
    }
    private void offlineOpen() { open(this::offline); }
    private void offline() { offline(false); }
    private final HashSet<Long> deletingDownloads = new HashSet<>();

    private void offline(boolean musicOnly) {
        page(musicOnly ? "音乐库" : "音浪", "本机下载");
        body.addView(button("刷新", false, this::refresh));
        gap(body, 12);
        List<JSONObject> entries = objects(sources.registered("offline"));
        if (musicOnly) entries.removeIf(entry -> entry.optBoolean("book"));
        if (entries.isEmpty()) { note(body, "手机下载的歌曲和章节会显示在这里。"); return; }
        var service = getSystemService(DownloadManager.class);
        for (int i = entries.size() - 1; i >= 0; i--) {
            JSONObject entry = entries.get(i);
            long id = entry.optLong("id");
            Uri uri = service.getUriForDownloadedFile(id);
            String title = entry.optString("title");
            int status = DownloadManager.STATUS_FAILED;
            try (var result = service.query(new DownloadManager.Query().setFilterById(id))) {
                if (result != null && result.moveToFirst()) status = result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
            String description = switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL -> "已保存 · 点击播放";
                case DownloadManager.STATUS_FAILED -> "下载失败或文件已删除";
                default -> "下载中";
            };
            String quality = qualityLabel(entry.optString("quality"));
            boolean book = entry.optBoolean("book");
            View delete = book || id <= 0 ? null : iconButton(UiIcon.Kind.DELETE, "删除本机下载的 " + title,
                false, 48, 18, true, () -> deleteDownload(id, title));
            row(body, title, quality.isEmpty() ? description : description + " · " + quality, "",
                book ? UiIcon.Kind.BOOK : UiIcon.Kind.SONG, false, delete, () -> {
                if (uri != null) PlaybackService.queue(this, List.of(new MediaEntry(uri.toString(), title, entry.optString("collection"), "", "offline", entry)),
                    0, book);
                else toast(description);
            });
        }
    }

    private void deleteDownload(long id, String title) {
        if (deletingDownloads.contains(id)) return;
        dialog().setTitle("删除本机歌曲？").setMessage("「" + title + "」将从本机下载中移除。")
            .setNegativeButton("取消", null).setPositiveButton("删除", (confirm, index) -> {
                if (!deletingDownloads.add(id)) return;
                int pageId = generation;
                work(() -> {
                    var downloads = getSystemService(DownloadManager.class);
                    if (downloads.remove(id) == 0) {
                        try (var item = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                            if (item != null && item.moveToFirst()) throw new IllegalStateException("系统未能删除这首歌");
                        }
                    }
                    return null;
                }, (result, error) -> {
                    deletingDownloads.remove(id);
                    if (error != null) { toast(error); return; }
                    var remaining = new JSONArray();
                    for (JSONObject entry : objects(sources.registered("offline"))) {
                        if (entry.optLong("id") != id) remaining.put(entry);
                    }
                    prefs.edit().putString("offline", remaining.toString()).apply();
                    if (current(pageId)) refresh();
                    toast("已删除本机歌曲");
                });
            }).show();
    }
    private boolean playerDark() { return prefs.getBoolean("player-dark", true); }
    private int playerInk() { return playerDark() ? 0xfffffbff : 0xff291e24; }
    private int playerMuted() { return playerDark() ? 0xffece4ef : 0xff624f55; }
    private int playerAccent() { return playerDark() ? 0xffffcedd : 0xff9a3e54; }
    private int playerBackground() { return playerDark() ? AlbumBackdrop.FALLBACK : AlbumBackdrop.LIGHT_FALLBACK; }
    private int playerTouch() { return playerDark() ? 0x35ffffff : 0x223d2732; }

    private GradientDrawable playerSurface(int radius) {
        if (playerDark()) return glass(true, radius);
        var surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xccffffff, 0xa8f3e5e1});
        surface.setCornerRadius(dp(radius));
        surface.setStroke(dp(1), 0x435e414b);
        return surface;
    }

    private FrameLayout playerIcon(UiIcon.Kind kind, String label, Runnable action) {
        return playerIcon(kind, label, 48, 20, true, action);
    }

    private FrameLayout playerIcon(UiIcon.Kind kind, String label, int target, int glyph, boolean surface, Runnable action) {
        var button = iconButton(kind, label, playerDark(), target, glyph, false, action);
        iconState(button, kind, playerInk(), label);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(playerTouch()),
            surface ? playerSurface(target / 2) : null, shape(Color.WHITE, target / 2)));
        return button;
    }

    private void addPlayerTool(LinearLayout tools, FrameLayout button) {
        tools.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    private void openPlayer() {
        playerPanel = PlayerPanel.ARTWORK;
        open(this::player);
    }

    private void player() {
        PlaybackService player = PlaybackService.active;
        if (player == null || player.book || player.index() < 0 || player.index() >= player.entries().size()) playerPanel = PlayerPanel.ARTWORK;
        page(player != null && player.book ? "有声书" : "音乐", "正在播放", true);
        displayedPlayer = player;
        mini.setVisibility(View.GONE);
        if (player == null || player.index() < 0 || player.index() >= player.entries().size()) {
            fill(body, text("还没有正在播放的内容", 16, playerMuted(), false));
            return;
        }
        shownRevision = player.revision();
        lyricHighlight = -1;
        lyricManualUntil = 0;
        playerLyrics = List.of();
        backdrop = new AlbumBackdrop(playerDark());
        root.setBackground(backdrop);
        playerStage = new PlayerStage(this);
        body.addView(playerStage, new LinearLayout.LayoutParams(-1, 0, 1));
        String cover = playbackCover(player);
        int pageId = generation;
        ImageView artwork = art(cover, 275, player.book, bitmap -> {
            if (current(pageId)) applyPlayerPalette(bitmap);
        });
        if (cover.isEmpty()) {
            artwork.setImageResource(player.book ? R.drawable.hero_books : R.drawable.yinlang_neon);
            artwork.setBackground(shape(player.book ? SAND : ONYX, 22));
        }
        if (!player.book && !coverStyle()) {
            recordArt = new RecordArt(this, artwork);
        } else {
            artwork.setBackground(playerSurface(19));
            artwork.setElevation(dp(8));
        }
        playerArtwork = recordArt == null ? artwork : recordArt;
        playerStage.setArtwork(playerArtwork, player.book);
        if (!player.book) playerStage.setOnSwipe(() -> toggleLyrics(player));
        playerArtwork.setContentDescription(recordArt != null ? "唱片" : "封面");
        var footer = column();
        playerFooter = footer;
        boolean compact = getResources().getConfiguration().screenHeightDp < 560;
        footer.setPadding(dp(25), dp(compact ? 3 : 10), dp(25), dp(compact ? 3 : 12));
        root.addView(footer, root.indexOfChild(mini), new LinearLayout.LayoutParams(-1, -2));
        var metadata = line();
        playerMetadata = metadata;
        var names = column();
        TextView title = text(player.title, 23, playerInk(), true);
        title.setMaxLines(compact ? 1 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        fill(names, title);
        gap(names, 6);
        var subtitle = text(player.subtitle, 14, playerMuted(), false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        fill(names, subtitle);
        weight(metadata, names);
        MediaEntry entry = player.entries().get(player.index());
        if (!player.book && !entry.source().equals("offline")) {
            space(metadata, 6);
            playerFavorite = playerIcon(UiIcon.Kind.STAR, "收藏歌曲", 48, 20, false, () -> updatePlayerFavorite(toggleLike(entry)));
            updatePlayerFavorite(liked(entry));
            metadata.addView(playerFavorite);
        }
        space(metadata, 7);
        var options = playerIcon(UiIcon.Kind.ELLIPSIS, "播放器菜单", 48, 20, false, () -> showPlayerMenu(player));
        metadata.addView(options);
        fill(footer, metadata);
        gap(footer, compact ? 3 : 8);
        seek = playerSlider("播放进度", 7);
        seek.setMax(Math.max(1, player.duration()));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                elapsed.setText(time(progress));
                if (!tracking) player.seek(progress);
                updateLyrics(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                tracking = false;
                player.seek(bar.getProgress());
            }
        });
        seek.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        fill(footer, seek);
        gap(footer, 4);
        var timings = line();
        elapsed = text("0:00", 12, playerMuted(), false);
        totalTime = text("0:00", 12, playerMuted(), false);
        weight(timings, elapsed);
        totalTime.setGravity(Gravity.END);
        weight(timings, totalTime);
        fill(footer, timings);
        gap(footer, compact ? 2 : 5);
        playerStatus = text("", 12, playerMuted(), true);
        playerStatus.setGravity(Gravity.CENTER);
        playerStatus.setMinHeight(dp(20));
        playerStatus.setSingleLine();
        playerStatus.setEllipsize(TextUtils.TruncateAt.END);
        playerStatus.setOnClickListener(view -> {
            if (!player.error.isEmpty()) dialog().setTitle("播放失败原因").setMessage(player.error)
                .setPositiveButton("关闭", null).show();
            else showAirPlayPicker(player);
        });
        fill(footer, playerStatus);

        gap(footer, compact ? 2 : 10);
        var controls = line();
        controls.setGravity(Gravity.CENTER);
        controls.addView(playerIcon(UiIcon.Kind.PREVIOUS_TRACK, player.book ? "上一集" : "上一首",
            compact ? 50 : 62, compact ? 28 : 33, false, () -> player.skip(-1)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        playToggle = playerIcon(UiIcon.Kind.PLAY, "播放", compact ? 57 : 70, compact ? 36 : 44, false, player::toggle);
        controls.addView(playToggle);
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(playerIcon(UiIcon.Kind.NEXT_TRACK, player.book ? "下一集" : "下一首",
            compact ? 50 : 62, compact ? 28 : 33, false, () -> player.skip(1)));
        controls.setPadding(dp(24), 0, dp(24), 0);
        fill(footer, controls);
        if (!compact) addPlayerVolume(footer);
        else gap(footer, 5);

        var tools = line();
        tools.setGravity(Gravity.CENTER);
        int toolSize = Math.min(48, Math.max(36, (getResources().getConfiguration().screenWidthDp - 60) / 6));
        if (player.book) {
            addPlayerTool(tools, playerIcon(UiIcon.Kind.BACK_15, "后退 15 秒", toolSize, 22, false, () -> player.seek(player.position() - 15_000)));
            addPlayerTool(tools, playerIcon(UiIcon.Kind.FORWARD_30, "前进 30 秒", toolSize, 22, false, () -> player.seek(player.position() + 30_000)));
        } else {
            lyricsControl = playerIcon(UiIcon.Kind.LYRICS, "显示歌词", toolSize, 22, false, () -> toggleLyrics(player));
            addPlayerTool(tools, lyricsControl);
            playerMode = playerIcon(modeIcon(player.mode()), player.mode().label, toolSize, 22, false, () -> {
                var values = PlayOrder.Mode.values();
                player.setMode(values[(player.mode().ordinal() + 1) % values.length]);
                updatePlayerMode(player);
                toast(player.mode().label);
            });
            playerMode.setOnLongClickListener(view -> {
                choosePlayMode(player, () -> updatePlayerMode(player));
                return true;
            });
            addPlayerTool(tools, playerMode);
        }
        addPlayerTool(tools, playerIcon(playerDark() ? UiIcon.Kind.SUN : UiIcon.Kind.MOON,
            playerDark() ? "切换浅色播放器" : "切换深色播放器", toolSize, 21, false, () -> {
                prefs.edit().putBoolean("player-dark", !playerDark()).apply();
                refresh();
            }));
        sleepControl = playerIcon(UiIcon.Kind.TIMER, "定时关闭", toolSize, 21, false, () -> chooseSleep(player));
        addPlayerTool(tools, sleepControl);
        airPlayControl = playerIcon(UiIcon.Kind.AIRPLAY, "投送", toolSize, 22, false, () -> showAirPlayPicker(player));
        addPlayerTool(tools, airPlayControl);
        addPlayerTool(tools, playerIcon(UiIcon.Kind.QUEUE, "播放队列", toolSize, 22, false, () -> showQueue(player)));
        fill(footer, tools);
        updatePlayerPanel(player);
        updatePlayer(player);
    }

    private void applyPlayerPalette(Bitmap cover) {
        if (cover != null && backdrop != null) getWindow().setStatusBarColor(backdrop.shade(cover));
    }

    private void updatePlayer(PlaybackService player) {
        int position = player.position();
        int duration = player.duration();
        seek.setMax(Math.max(1, duration));
        seek.setEnabled(duration > 0);
        seek.setSecondaryProgress(Math.min(duration, player.bufferedPosition()));
        seek.setProgress(position);
        elapsed.setText(time(position));
        totalTime.setText(duration > 0 ? "−" + time(Math.max(0, duration - position)) : "—");
        boolean routeMessage = !player.airPlayMessage().isEmpty();
        String status = !player.error.isEmpty() ? "播放失败" : routeMessage ? "投送状态"
            : player.preparing() ? "正在缓冲…" : player.audioDetailsLabel();
        if (!status.contentEquals(playerStatus.getText())) playerStatus.setText(status);
        playerStatus.setVisibility(status.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        playerStatus.setClickable(!player.error.isEmpty() || routeMessage);
        playerStatus.setFocusable(!player.error.isEmpty() || routeMessage);
        String detail = !player.error.isEmpty() ? "播放失败，查看原因" : routeMessage ? player.airPlayMessage() : status;
        if (!detail.contentEquals(playerStatus.getContentDescription() == null ? "" : playerStatus.getContentDescription())) {
            playerStatus.setContentDescription(detail);
        }
        iconState(playToggle, player.wantsPause() ? UiIcon.Kind.PAUSE : UiIcon.Kind.PLAY, playerInk(), player.wantsPause() ? "暂停" : "播放");
        if (airPlayControl != null) iconState(airPlayControl, UiIcon.Kind.AIRPLAY,
            player.airPlayConnected() ? playerAccent() : playerInk(),
            player.airPlayName().isEmpty() ? "投送" : "投送 · " + player.airPlayName());
        if (recordArt != null) recordArt.playing(player.playing() && !player.preparing() && !lyricsVisible());
        updateLyrics(position);
        updatePlayerVolume();
        boolean sleepActive = player.sleepAfterItem() || player.sleepRemaining() > 0;
        iconState(sleepControl, UiIcon.Kind.TIMER, sleepActive ? playerAccent() : playerInk(),
            player.sleepAfterItem() ? "播完关闭" : sleepActive ? "定时关闭剩余 " + time((int) player.sleepRemaining()) : "定时关闭");
    }

    private SeekBar playerSlider(String description, int height) {
        var slider = new BufferedSeekBar(this, height,
            playerDark() ? 0xff414149 : 0xffd1cdce,
            playerDark() ? 0xff85858f : 0xff8b8184, playerInk());
        slider.setContentDescription(description);
        slider.setMinimumHeight(dp(34));
        slider.setPadding(0, 0, 0, 0);
        return slider;
    }

    private void addPlayerVolume(LinearLayout footer) {
        var volume = line();
        volume.addView(new UiIcon(this, UiIcon.Kind.VOLUME_LOW, 15, playerMuted()), new LinearLayout.LayoutParams(dp(21), dp(36)));
        space(volume, 7);
        var manager = getSystemService(AudioManager.class);
        int stream = AudioManager.STREAM_MUSIC;
        playerVolume = playerSlider("媒体音量", 5);
        playerVolume.setMax(manager.getStreamMaxVolume(stream));
        playerVolume.setMin(manager.getStreamMinVolume(stream));
        playerVolume.setEnabled(!manager.isVolumeFixed());
        playerVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                var player = PlaybackService.active;
                if (player != null && player.airPlayConnected()) {
                    player.adjustAirPlayVolume(progress);
                    return;
                }
                try { manager.setStreamVolume(stream, progress, 0); }
                catch (SecurityException unavailable) { bar.setEnabled(false); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { draggingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { draggingVolume = false; }
        });
        weight(volume, playerVolume);
        space(volume, 7);
        var output = playerIcon(UiIcon.Kind.VOLUME_HIGH, "打开系统音量", 48, 19, false, () -> {
            var player = PlaybackService.active;
            if (player != null && player.airPlayConnected()) { showAirPlayPicker(player); return; }
            try { manager.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI); }
            catch (SecurityException unavailable) { toast("此设备无法从应用调节音量"); }
        });
        volume.addView(output);
        fill(footer, volume);
    }

    private void updatePlayerVolume() {
        if (playerVolume == null || draggingVolume) return;
        var manager = getSystemService(AudioManager.class);
        var player = PlaybackService.active;
        boolean casting = player != null && player.airPlayConnected();
        playerVolume.setMin(casting ? 0 : manager.getStreamMinVolume(AudioManager.STREAM_MUSIC));
        playerVolume.setMax(casting ? 100 : manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        playerVolume.setEnabled(casting || !manager.isVolumeFixed());
        playerVolume.setProgress(casting ? player.airPlayVolume() : manager.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    private void showAirPlayPicker(PlaybackService player) {
        if (airPlayPicker != null) airPlayPicker.dismiss();
        new AirPlayPicker(player).show();
    }

    private void focusDialogInput(AlertDialog dialog, EditText input) {
        var dialogWindow = dialog.getWindow();
        if (dialogWindow == null) return;
        // AlertDialog disables IME interaction when its initial view has no editor.
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        input.setShowSoftInputOnFocus(true);
        input.requestFocus();
        input.post(() -> {
            if (!dialog.isShowing() || !input.hasFocus()) return;
            var insets = input.getWindowInsetsController();
            if (insets != null) insets.show(android.view.WindowInsets.Type.ime());
            else getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private final class AirPlayPicker {
        private final PlaybackService player;
        private final LinearLayout content = column();
        private final AirPlayDiscovery discovery;
        private final DlnaController dlnaDiscovery;
        private List<AirPlayDiscovery.Device> devices = List.of();
        private List<DlnaController.Device> dlnaDevices = List.of();
        private String searchError = "", last = "";
        private boolean searched;
        private AlertDialog window;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!window.isShowing()) return;
                if (PlaybackService.active != player) { window.dismiss(); return; }
                render(false);
                main.postDelayed(this, 350);
            }
        };
        private final Runnable searchTimeout = () -> { searched = true; render(true); };

        AirPlayPicker(PlaybackService player) {
            this.player = player;
            discovery = new AirPlayDiscovery(MainActivity.this, (list, error) -> {
                devices = list;
                searchError = error;
                render(true);
            });
            dlnaDiscovery = new DlnaController(MainActivity.this, (list, error) -> {
                dlnaDevices = list;
                if (!error.isEmpty() && devices.isEmpty()) searchError = error;
                render(true);
            });
        }

        void show() {
            content.setPadding(dp(20), dp(10), dp(20), dp(12));
            var scroll = new ScrollView(MainActivity.this);
            scroll.addView(content);
            window = dialog().setTitle("投送").setView(scroll).setNegativeButton("关闭", null).create();
            airPlayPicker = window;
            window.setOnDismissListener(ignored -> {
                discovery.close();
                dlnaDiscovery.close();
                main.removeCallbacks(tick);
                main.removeCallbacks(searchTimeout);
                if (airPlayPicker == window) airPlayPicker = null;
            });
            window.show();
            render(true);
            discovery.start();
            dlnaDiscovery.start();
            main.postDelayed(searchTimeout, 5000);
            main.postDelayed(tick, 350);
        }

        private void render(boolean refreshDevices) {
            String device = player.airPlayName();
            String message = player.airPlayMessage();
            boolean connected = player.airPlayConnected();
            boolean pin = player.airPlayPinRequired();
            String state = device + "\n" + message + "\n" + connected + "\n" + pin;
            // Preserve an entered PIN while discovery continues in the background.
            if (state.equals(last) && (!refreshDevices || !device.isEmpty())) return;
            last = state;
            content.removeAllViews();
            if (!message.isEmpty()) note(content, message);
            if (!device.isEmpty()) {
                if (connected) {
                    note(content, "正在投送到 " + device);
                    var volume = new SeekBar(MainActivity.this);
                    volume.setContentDescription("接收设备音量");
                    volume.setMax(100);
                    volume.setProgress(player.airPlayVolume());
                    volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override public void onStartTrackingTouch(SeekBar bar) {}
                        @Override public void onStopTrackingTouch(SeekBar bar) {}
                        @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                            if (user) player.adjustAirPlayVolume(value);
                        }
                    });
                    fill(content, volume);
                } else if (pin) {
                    EditText input = field("4 位配对码", "", InputType.TYPE_CLASS_NUMBER);
                    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
                    input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(4)});
                    fill(content, input);
                    Runnable pair = () -> {
                        String value = input.getText().toString();
                        if (!value.matches("[0-9]{4}")) { input.setError("请输入 4 位数字"); return; }
                        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
                        player.submitAirPlayPin(value);
                    };
                    input.setOnEditorActionListener((view, action, event) -> {
                        if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
                        pair.run();
                        return true;
                    });
                    fill(content, button("配对", true, pair));
                    focusDialogInput(window, input);
                }
                gap(content, 8);
                if (player.canPairAgain()) fill(content, button("重新配对", false, player::pairAirPlayAgain));
                fill(content, button(connected ? "停止投送" : "取消连接", false, player::disconnectAirPlay));
                return;
            }
            if (devices.isEmpty() && dlnaDevices.isEmpty()) {
                note(content, !searchError.isEmpty() ? searchError : searched
                    ? "暂未发现接收设备。请确认设备已开启音频接收，并与手机连在同一局域网。"
                    : "正在搜索同一局域网的接收设备…");
            }
            for (var receiver : devices) {
                row(content, receiver.name(), "", "",
                    UiIcon.Kind.AIRPLAY, () -> {
                        if (receiver.mode() != AirPlayDiscovery.PASSWORD) player.connectAirPlay(receiver, "");
                        else {
                            var input = field("接收设备密码", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                            var passwordDialog = dialog().setTitle(receiver.name()).setView(input).setNegativeButton("取消", null)
                                .setPositiveButton("连接", (ignored, index) -> player.connectAirPlay(receiver, input.getText().toString())).show();
                            focusDialogInput(passwordDialog, input);
                        }
                    });
            }
            for (var receiver : dlnaDevices) {
                row(content, receiver.name(), "DLNA", "", UiIcon.Kind.AIRPLAY,
                    () -> player.connectDlna(receiver));
            }
        }
    }

    private void changePlayerSpeed(PlaybackService player) {
        float[] values = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};
        String[] labels = new String[values.length];
        int selected = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + "×";
            if (values[i] == player.speed) selected = i;
        }
        dialog().setTitle("播放速度").setSingleChoiceItems(labels, selected, (dialog, index) -> {
            player.changeSpeed(values[index]);
            dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }

    private void chooseSleep(PlaybackService player) {
        int[] minutes = {15, 30, 45, 60, 90, -1, 0};
        dialog().setTitle("定时关闭").setItems(
            new String[]{"15 分钟", "30 分钟", "45 分钟", "60 分钟", "90 分钟", player.book ? "播完本集" : "播完本首", "关闭定时"},
            (dialog, index) -> { player.setSleep(minutes[index]); if (seek != null && displayedPlayer == player) updatePlayer(player); }).show();
    }

    private void choosePlayMode(PlaybackService player, Runnable changed) {
        var modes = PlayOrder.Mode.values();
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) names[i] = modes[i].label;
        dialog().setTitle("播放模式").setSingleChoiceItems(names, player.mode().ordinal(), (dialog, index) -> {
            player.setMode(modes[index]);
            changed.run();
            dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }

    private void updatePlayerFavorite(boolean saved) {
        UiIcon.Kind icon = saved ? UiIcon.Kind.STAR_FILLED : UiIcon.Kind.STAR;
        int color = saved ? playerAccent() : playerInk();
        String label = saved ? "取消收藏" : "收藏歌曲";
        if (playerFavorite != null) iconState(playerFavorite, icon, color, label);
        if (lyricFavorite != null) iconState(lyricFavorite, icon, color, label);
    }

    private void updatePlayerMode(PlaybackService player) {
        if (playerMode == null) return;
        iconState(playerMode, modeIcon(player.mode()), playerInk(), player.mode().label);
    }

    private UiIcon.Kind modeIcon(PlayOrder.Mode mode) {
        return switch (mode) {
            case SEQUENTIAL -> UiIcon.Kind.SEQUENCE;
            case REPEAT_ALL -> UiIcon.Kind.REPEAT;
            case REPEAT_ONE -> UiIcon.Kind.REPEAT_ONE;
            case SHUFFLE -> UiIcon.Kind.SHUFFLE;
        };
    }

    private boolean coverStyle() { return prefs.getBoolean("player-cover-style", true); }
    private String preferredQuality() { return prefs.getString("music-quality", "320k"); }
    private String qualityLabel(String quality) {
        return MusicQuality.label(quality);
    }

    private void chooseQuality(Runnable changed) {
        var choices = MusicQuality.values();
        String[] labels = java.util.Arrays.stream(choices).map(quality -> quality.label).toArray(String[]::new);
        int checked = java.util.Arrays.asList(labels).indexOf(qualityLabel(preferredQuality()));
        dialog().setTitle("默认播放音质")
            .setSingleChoiceItems(labels, checked, (dialog, index) -> {
                dialog.dismiss();
                String selected = choices[index].key;
                if (!selected.equals(preferredQuality())) {
                    prefs.edit().putString("music-quality", selected).apply();
                    changed.run();
                }
            }).setNegativeButton("取消", null).show();
    }

    private void choosePlayerStyle() {
        dialog().setTitle("播放器样式")
            .setSingleChoiceItems(new String[]{"旋转黑胶", "方形封面"}, coverStyle() ? 1 : 0, (dialog, index) -> {
                dialog.dismiss();
                if (coverStyle() != (index == 1)) {
                    prefs.edit().putBoolean("player-cover-style", index == 1).apply();
                    playerPanel = PlayerPanel.ARTWORK;
                    if (playerPage) refresh();
                }
            }).setNegativeButton("取消", null).show();
    }

    private void chooseEffect(PlaybackService player) {
        if (effectsDialog != null) effectsDialog.dismiss();
        new EffectsPanel(player).show();
    }

    private final class EffectsPanel {
        private final PlaybackService player;
        private final EffectSettings settings;
        private final android.app.Dialog dialog = new android.app.Dialog(MainActivity.this);
        private final List<Runnable> bindings = new ArrayList<>();
        private final List<View> controls = new ArrayList<>();
        private final TextView status = text("", 13, MUTED, false);
        private final Switch enabled;
        private boolean syncing;
        private boolean pitchChanged;
        private final Runnable applyPending = this::commit;
        private final Runnable monitor = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                if (PlaybackService.active != player || player.book || !playerPage) { dialog.dismiss(); return; }
                status.setText(player.effectStatus());
                main.postDelayed(this, 700);
            }
        };

        EffectsPanel(PlaybackService player) {
            this.player = player;
            settings = player.effectSettings();
            enabled = settingSwitch("音效开关", settings.enabled);
        }

        void show() {
            effectsDialog = dialog;
            var sheet = column();
            sheet.setPadding(dp(16), dp(12), dp(16), dp(12));
            var toolbar = line();
            var title = text("均衡器与音效调节", 20, INK, true);
            title.setAccessibilityHeading(true);
            weight(toolbar, title);
            toolbar.addView(iconButton(UiIcon.Kind.CLOSE, "关闭音效设置", false, dialog::dismiss));
            fill(sheet, toolbar);
            status.setPadding(dp(3), dp(3), dp(3), dp(9));
            fill(sheet, status);

            var scroll = new ScrollView(MainActivity.this);
            var panel = column();
            scroll.addView(panel);
            sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            var master = settingsCard(panel);
            fill(master, enabled);
            enabled.setOnCheckedChangeListener((view, value) -> {
                if (syncing) return;
                settings.enabled = value;
                changed(true, true);
            });
            var masterActions = line();
            weight(masterActions, button("恢复原声", false, () -> {
                settings.enabled = false;
                changed(true, true);
            }));
            space(masterActions, 8);
            weight(masterActions, button("全部重置", false, () -> {
                settings.reset();
                changed(true, true);
            }));
            fill(master, masterActions);

            var eq = card(panel, "十段均衡器", "重置 0 dB", () -> { settings.resetEq(); changed(false, true); });
            var presetActions = line();
            TextView selection = button("", false, this::presetPicker);
            controls.add(selection);
            weight(presetActions, selection);
            space(presetActions, 8);
            var save = button("保存预设", false, this::savePreset);
            controls.add(save);
            presetActions.addView(save);
            fill(eq, presetActions);
            bindings.add(() -> selection.setText("快速预设 · " + (settings.presetName().isEmpty() ? "平直" : settings.presetName()) + " ▾"));
            gap(eq, 6);
            for (int i = 0; i < EffectSettings.FREQUENCIES.length; i++) {
                int band = i;
                int frequency = EffectSettings.FREQUENCIES[i];
                slider(eq, frequency < 1000 ? frequency + " Hz" : frequency / 1000 + " kHz",
                    -12, 12, () -> settings.gain[band], value -> settings.gain[band] = value,
                    value -> String.format(Locale.ROOT, "%+d dB", value), () -> true, false);
            }
            var reverb = card(panel, "环境混响", "关闭混响", () -> {
                settings.room = EffectSettings.Room.OFF; changed(false, true);
            });
            var room = button("", false, () -> {
                var rooms = EffectSettings.Room.values();
                String[] options = java.util.Arrays.stream(rooms).map(item -> item.label).toArray(String[]::new);
                dialog().setTitle("环境混响").setSingleChoiceItems(options, settings.room.ordinal(), (picker, index) -> {
                    settings.room = rooms[index];
                    picker.dismiss();
                    changed(false, true);
                }).setNegativeButton("取消", null).show();
            });
            controls.add(room);
            fill(reverb, room);
            bindings.add(() -> room.setText("环境 · " + settings.room.label + " ▾"));
            slider(reverb, "原声 DRY", 0, 100, () -> settings.dry, value -> settings.dry = value,
                value -> value + "%", () -> settings.room != EffectSettings.Room.OFF, false);
            slider(reverb, "混响 WET", 0, 100, () -> settings.room == EffectSettings.Room.OFF ? 0 : settings.wet, value -> settings.wet = value,
                value -> value + "%", () -> settings.room != EffectSettings.Room.OFF, false);

            var pitch = card(panel, "音调升降", "重置原调", () -> { settings.pitch = 100; changed(true, true); });
            slider(pitch, "音调", 50, 200, () -> settings.pitch, value -> settings.pitch = value,
                value -> String.format(Locale.ROOT, "%.2f×", value / 100f), () -> true, true);

            var spatial = card(panel, "立体环绕", "恢复默认", () -> { settings.resetSurround(); changed(false, true); });
            var surround = settingSwitch("耳机环绕", settings.surround);
            controls.add(surround);
            bindings.add(() -> surround.setChecked(settings.surround));
            surround.setOnCheckedChangeListener((view, value) -> {
                if (syncing) return;
                settings.surround = value;
                changed(false, true);
            });
            fill(spatial, surround);
            slider(spatial, "环绕速度", 1, 50, () -> settings.speed, value -> settings.speed = value,
                Integer::toString, () -> settings.surround, false);
            slider(spatial, "声音距离", 1, 30, () -> settings.distance, value -> settings.distance = value,
                Integer::toString, () -> settings.surround, false);

            dialog.setContentView(sheet);
            dialog.setOnDismissListener(ignored -> {
                main.removeCallbacks(monitor);
                main.removeCallbacks(applyPending);
                applyPending.run();
                if (effectsDialog == dialog) effectsDialog = null;
            });
            sync();
            showBottomSheet(dialog, sheet, .91f);
            monitor.run();
        }

        private LinearLayout card(LinearLayout panel, String title, String reset, Runnable action) {
            gap(panel, 8);
            var card = settingsCard(panel);
            var header = line();
            var label = text(title, 16, INK, true);
            label.setAccessibilityHeading(true);
            weight(header, label);
            var button = button(reset, false, action);
            controls.add(button);
            header.addView(button);
            fill(card, header);
            gap(card, 6);
            return card;
        }

        private void slider(LinearLayout parent, String label, int min, int max, java.util.function.IntSupplier get,
                            java.util.function.IntConsumer set, java.util.function.IntFunction<String> format,
                            java.util.function.BooleanSupplier available, boolean changesPitch) {
            var row = line();
            TextView name = text(label, 12, INK, true);
            row.addView(name, new LinearLayout.LayoutParams(dp(76), -2));
            var bar = new SeekBar(MainActivity.this);
            bar.setMax(max - min);
            bar.setContentDescription(label);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
            weight(row, bar);
            TextView value = text("", 12, ACCENT, true);
            value.setGravity(Gravity.END);
            row.addView(value, new LinearLayout.LayoutParams(dp(55), -2));
            row.setMinimumHeight(dp(39));
            bindings.add(() -> {
                bar.setEnabled(settings.enabled && available.getAsBoolean());
                bar.setProgress(get.getAsInt() - min);
                value.setText(format.apply(get.getAsInt()));
            });
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar view, int progress, boolean user) {
                    if (!user || syncing) return;
                    set.accept(progress + min);
                    changed(changesPitch, false);
                }
                @Override public void onStartTrackingTouch(SeekBar view) {}
                @Override public void onStopTrackingTouch(SeekBar view) {
                    main.removeCallbacks(applyPending);
                    applyPending.run();
                }
            });
            fill(parent, row);
        }

        private void changed(boolean pitch, boolean immediate) {
            pitchChanged |= pitch;
            sync();
            if (immediate) {
                main.removeCallbacks(applyPending);
                applyPending.run();
            } else if (!main.hasCallbacks(applyPending)) main.postDelayed(applyPending, 60);
        }

        private void commit() {
            if (PlaybackService.active == player) player.updateEffects(pitchChanged);
            pitchChanged = false;
            settings.save(prefs);
            status.setText(player.effectStatus());
        }

        private void sync() {
            syncing = true;
            enabled.setChecked(settings.enabled);
            for (View control : controls) {
                control.setEnabled(settings.enabled);
                control.setAlpha(settings.enabled ? 1f : .56f);
            }
            for (Runnable binding : bindings) binding.run();
            syncing = false;
        }

        private void presetPicker() {
            var curves = new ArrayList<JSONArray>();
            var title = new ArrayList<String>();
            for (EffectSettings.Preset preset : EffectSettings.Preset.values()) {
                title.add(preset.label);
                var bands = new JSONArray();
                for (int value : preset.gain) bands.put(value);
                curves.add(bands);
            }
            for (JSONObject saved : objects(sources.registered("effect-presets"))) {
                title.add(saved.optString("name") + " · 自定义");
                curves.add(saved.optJSONArray("bands"));
            }
            dialog().setTitle("快速预设").setItems(title.toArray(new String[0]), (picker, index) -> {
                settings.restoreBands(curves.get(index));
                changed(false, true);
            }).show();
        }

        private void savePreset() {
            prompt("保存均衡器预设", "预设名称", "", name -> {
                if (name.isEmpty()) return;
                var saved = new JSONArray();
                saved.put(MediaSources.args("name", name, "bands", settings.bands()));
                for (JSONObject old : objects(sources.registered("effect-presets"))) {
                    if (!name.equals(old.optString("name")) && saved.length() < 24) saved.put(old);
                }
                prefs.edit().putString("effect-presets", saved.toString()).apply();
                toast("已保存预设");
            });
        }
    }

    private void updateKeepScreenOn() {
        if (playerPage && prefs.getBoolean("player-keep-screen-on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void playbackSettings(PlaybackService player) {
        String[] options = {"耳机断开时暂停", "播放页保持屏幕常亮"};
        boolean[] values = {prefs.getBoolean("player-pause-on-unplug", true), prefs.getBoolean("player-keep-screen-on", false)};
        dialog().setTitle("播放设置").setMultiChoiceItems(options, values, (dialog, index, value) -> {
            prefs.edit().putBoolean(index == 0 ? "player-pause-on-unplug" : "player-keep-screen-on", value).apply();
            updateKeepScreenOn();
        }).setNeutralButton(player.speed + "× 倍速", (dialog, which) -> changePlayerSpeed(player))
            .setPositiveButton("完成", null).show();
    }

    private void showPlayerMenu(PlaybackService player) {
        if (player != PlaybackService.active || !playerPage || player.index() < 0) return;
        if (playerMenu != null) playerMenu.dismiss();
        MediaEntry entry = player.entries().get(player.index());
        boolean offline = entry.source().equals("offline");
        android.app.Dialog dialog = new android.app.Dialog(this);
        playerMenu = dialog;
        var sheet = column();
        sheet.setPadding(dp(16), 0, dp(16), dp(12));

        var grip = new FrameLayout(this);
        var gripBar = new View(this);
        gripBar.setBackground(shape(0xffa7b5bd, 4));
        grip.addView(gripBar, new FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER));
        sheet.addView(grip, new LinearLayout.LayoutParams(-1, dp(20)));
        grip.setContentDescription("关闭播放器菜单");
        grip.setOnClickListener(view -> dialog.dismiss());
        float[] start = new float[1];
        grip.setOnTouchListener((view, motion) -> {
            switch (motion.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN -> start[0] = motion.getRawY();
                case android.view.MotionEvent.ACTION_UP -> {
                    if (motion.getRawY() - start[0] >= dp(30)) dialog.dismiss();
                    else view.performClick();
                }
                default -> {}
            }
            return true;
        });
        var header = line();
        String coverUrl = coverAddress(entry);
        var cover = art(coverUrl, 47, player.book);
        if (coverUrl.isEmpty()) cover.setImageResource(player.book ? R.drawable.hero_books : R.drawable.yinlang_neon);
        header.addView(cover, new LinearLayout.LayoutParams(dp(47), dp(47)));
        space(header, 12);
        var metadata = column();
        var title = text(entry.title(), 17, INK, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        fill(metadata, title);
        if (!entry.subtitle().trim().isEmpty()) {
            gap(metadata, 6);
            var subtitle = text(entry.subtitle(), 13, MUTED, false);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            fill(metadata, subtitle);
        }
        weight(header, metadata);
        header.addView(iconButton(UiIcon.Kind.CLOSE, "关闭播放器菜单", false, dialog::dismiss));
        fill(sheet, header);
        gap(sheet, 14);

        var scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        var contents = column();
        scroll.addView(contents);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        var actions = line();
        var book = player.book && entry.raw().has("_book") ? MediaSources.args("bookUrl", entry.raw().optString("_book"),
            "title", entry.subtitle(), "coverUrl", entry.cover()) : null;
        if (!offline && (!player.book || book != null)) {
            boolean saved = player.book ? objects(sources.registered("shelf")).stream().anyMatch(item ->
                entry.source().equals(item.optString("_source")) && book.optString("bookUrl").equals(item.optString("bookUrl"))) : liked(entry);
            menuAction(actions, saved ? UiIcon.Kind.STAR_FILLED : UiIcon.Kind.STAR, saved ? "取消收藏" : "加入收藏", () -> {
                dialog.dismiss();
                boolean added = player.book ? rememberBook("shelf", entry.source(), book, true) : toggleLike(entry);
                if (!player.book) updatePlayerFavorite(added);
                toast(added ? "已加入收藏" : "已取消收藏");
            });
        }
        if (!player.book) menuAction(actions, modeIcon(player.mode()), "播放模式", () -> {
            dialog.dismiss();
            choosePlayMode(player, () -> updatePlayerMode(player));
        });
        menuAction(actions, UiIcon.Kind.SLIDERS, "播放速度", () -> { dialog.dismiss(); changePlayerSpeed(player); });
        menuAction(actions, UiIcon.Kind.QUEUE, "播放队列", () -> { dialog.dismiss(); showQueue(player); });
        menuAction(actions, UiIcon.Kind.TIMER, "定时关闭", () -> { dialog.dismiss(); chooseSleep(player); });
        fill(contents, actions);
        gap(contents, 12);

        if (!player.book && entry.source().equals("library") && !entry.id().isEmpty()) {
            menuRow(contents, UiIcon.Kind.INFO, "查看", "歌曲详情、专辑与艺术家", () -> {
                dialog.dismiss();
                showLibrarySongView(entry);
            });
        }
        if (!offline && !player.book) menuRow(contents, UiIcon.Kind.DOWNLOAD, "下载到手机", "", () -> {
            dialog.dismiss();
            chooseDownloadQuality(List.of(entry), false, "", player.index() + 1, false);
        });
        if (!offline && !player.book && !entry.source().equals("library")) menuRow(contents, UiIcon.Kind.SERVER, "推送到服务端下载", serverDownloadEnabled() ? "" : "未配置", () -> {
            dialog.dismiss();
            if (!serverDownloadEnabled()) { open(this::settings); return; }
            chooseDownloadQuality(List.of(entry), false, "", player.index() + 1, true);
        });
        menuRow(contents, UiIcon.Kind.AIRPLAY, "投送", player.airPlayName(), () -> {
            dialog.dismiss();
            showAirPlayPicker(player);
        });
        if (!player.book) {
            menuRow(contents, UiIcon.Kind.ALBUM, "播放器样式", coverStyle() ? "方形封面" : "旋转黑胶",
                () -> { dialog.dismiss(); choosePlayerStyle(); });
            String resolved = player.resolvedQuality();
            boolean online = player.onlineMusic();
            String quality = online ? qualityLabel(resolved.isEmpty() ? preferredQuality() : resolved) : "原始音质";
            menuRow(contents, UiIcon.Kind.SONG, "音质", quality, online ? () -> { dialog.dismiss(); chooseQuality(player::reloadQuality); } : null);
            menuRow(contents, UiIcon.Kind.SLIDERS, "音效", player.effectSettings().hasEffects() ? "已开启" : "原声",
                () -> { dialog.dismiss(); chooseEffect(player); });
        }
        menuRow(contents, UiIcon.Kind.SETTINGS, "播放设置", "", () -> { dialog.dismiss(); playbackSettings(player); });
        Runnable syncVolume = menuVolume(contents, dialog);
        dialog.setContentView(sheet);
        dialog.setOnDismissListener(ignored -> {
            main.removeCallbacks(syncVolume);
            if (playerMenu == dialog) playerMenu = null;
        });
        showBottomSheet(dialog, sheet, .78f);
        syncVolume.run();
    }

    private boolean serverDownloadEnabled() {
        return prefs.getBoolean("server-enabled", false) && !prefs.getString("server-url", "").trim().isEmpty();
    }

    private void menuAction(LinearLayout actions, UiIcon.Kind icon, String label, Runnable action) {
        if (actions.getChildCount() > 0) space(actions, 7);
        var tile = column();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(2), dp(10), dp(2), dp(9));
        tile.addView(new UiIcon(this, icon, 22, ACCENT), new LinearLayout.LayoutParams(dp(28), dp(28)));
        gap(tile, 6);
        var caption = text(label, 12, INK, true);
        caption.setGravity(Gravity.CENTER);
        fill(tile, caption);
        clickable(tile, PAPER, CARD_RADIUS, action);
        weight(actions, tile);
    }

    private void menuRow(LinearLayout menu, UiIcon.Kind icon, String label, String value, Runnable action) {
        var row = line();
        row.setMinimumHeight(dp(50));
        row.setPadding(dp(9), dp(6), dp(8), dp(6));
        row.addView(new UiIcon(this, icon, 20, ACCENT), new LinearLayout.LayoutParams(dp(31), dp(32)));
        weight(row, text(label, 15, INK, true));
        if (!value.isEmpty()) row.addView(text(value, 13, MUTED, false));
        if (action != null) {
            space(row, 9);
            row.addView(new UiIcon(this, UiIcon.Kind.NEXT, 15, MUTED), new LinearLayout.LayoutParams(dp(18), dp(24)));
            clickable(row, PAPER, CARD_RADIUS, action);
        } else row.setBackground(shape(PAPER, CARD_RADIUS));
        fill(menu, row);
        gap(menu, 5);
    }

    private Runnable menuVolume(LinearLayout menu, android.app.Dialog dialog) {
        var manager = getSystemService(AudioManager.class);
        int stream = AudioManager.STREAM_MUSIC;
        var row = line();
        row.setPadding(dp(9), dp(7), dp(8), dp(7));
        row.setBackground(shape(PAPER, CARD_RADIUS));
        row.addView(new UiIcon(this, UiIcon.Kind.VOLUME_HIGH, 20, ACCENT), new LinearLayout.LayoutParams(dp(31), dp(32)));
        row.addView(text("调节音量", 15, INK, true));
        space(row, 5);
        var slider = new SeekBar(this);
        slider.setContentDescription("媒体音量");
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setEnabled(!manager.isVolumeFixed());
        weight(row, slider);
        var value = text("", 13, MUTED, true);
        value.setMinWidth(dp(34));
        value.setGravity(Gravity.END);
        row.addView(value);
        fill(menu, row);
        final boolean[] dragging = {false};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    try { manager.setStreamVolume(stream, progress, 0); }
                    catch (SecurityException blocked) { slider.setEnabled(false); }
                }
                int max = Math.max(1, manager.getStreamMaxVolume(stream));
                value.setText(Math.round(manager.getStreamVolume(stream) * 100f / max) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging[0] = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { dragging[0] = false; }
        });
        return new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                if (!dragging[0]) {
                    int max = Math.max(1, manager.getStreamMaxVolume(stream));
                    int volume = manager.getStreamVolume(stream);
                    slider.setMax(max);
                    slider.setMin(manager.getStreamMinVolume(stream));
                    slider.setProgress(volume);
                    value.setText(Math.round(volume * 100f / max) + "%");
                }
                main.postDelayed(this, 350);
            }
        };
    }

    private void toggleLyrics(PlaybackService player) {
        if (player.book || !playerPage || displayedPlayer != player || shownRevision != player.revision()) return;
        if (fullScreenLyrics()) { toggleFullScreenLyrics(); return; }
        playerPanel = lyricsVisible() ? PlayerPanel.ARTWORK : PlayerPanel.LYRICS;
        updatePlayerPanel(player);
    }

    private boolean lyricsVisible() { return playerPanel != PlayerPanel.ARTWORK; }
    private boolean fullScreenLyrics() { return playerPage && playerPanel == PlayerPanel.FULLSCREEN_LYRICS; }

    private void toggleFullScreenLyrics() {
        if (!playerPage || !lyricsVisible() || lyricsScroll == null) return;
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            android.transition.TransitionManager.endTransitions(root);
            var transition = new android.transition.TransitionSet()
                .addTransition(new android.transition.ChangeBounds().addTarget(body).addTarget(playerStage).addTarget(lyricsScroll))
                .addTransition(new android.transition.Slide(Gravity.TOP).addTarget(playerHandle).addTarget(lyricTrack))
                .addTransition(new android.transition.Slide(Gravity.BOTTOM).addTarget(playerFooter));
            transition.setDuration(220);
            android.transition.TransitionManager.beginDelayedTransition(root, transition);
        }
        playerPanel = fullScreenLyrics() ? PlayerPanel.LYRICS : PlayerPanel.FULLSCREEN_LYRICS;
        updatePlayerPanel(displayedPlayer);
    }

    private void updatePlayerPanel(PlaybackService player) {
        if (player.book || playerArtwork == null) return;
        boolean lyrics = lyricsVisible();
        boolean fullscreen = fullScreenLyrics();
        if (lyrics && lyricsScroll == null) showLyrics(player);
        playerArtwork.setVisibility(lyrics ? View.INVISIBLE : View.VISIBLE);
        if (lyricsScroll != null) lyricsScroll.setVisibility(lyrics ? View.VISIBLE : View.INVISIBLE);
        if (lyricTrack != null) lyricTrack.setVisibility(lyrics && !fullscreen ? View.VISIBLE : View.GONE);
        playerHandle.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        playerFooter.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        if (fullscreen) sourceStatus.setVisibility(View.GONE);
        playerMetadata.setVisibility(lyrics ? View.GONE : View.VISIBLE);
        lyricsControl.setSelected(lyrics);
        lyricsControl.setBackground(ripple(lyrics ? playerTouch() : Color.TRANSPARENT, PILL_RADIUS, playerTouch()));
        lyricsControl.setContentDescription(lyrics ? (coverStyle() ? "返回封面" : "返回唱片") : "显示歌词");
        if (recordArt != null) recordArt.playing(player.playing() && !player.preparing() && !lyrics);
        if (lyrics) updateLyrics(player.position());
    }

    private void showLyrics(PlaybackService player) {
        lyricTrack = line();
        lyricTrack.setPadding(dp(8), dp(3), dp(8), dp(8));
        String url = playbackCover(player);
        var cover = art(url, 56, false);
        if (url.isEmpty()) cover.setImageResource(R.drawable.yinlang_neon);
        lyricTrack.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        space(lyricTrack, 11);
        var names = column();
        for (TextView label : List.of(text(player.title, 17, playerInk(), true), text(player.subtitle, 13, playerMuted(), false))) {
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            fill(names, label);
        }
        weight(lyricTrack, names);
        MediaEntry entry = player.entries().get(player.index());
        if (!entry.source().equals("offline")) {
            lyricFavorite = playerIcon(UiIcon.Kind.STAR, "收藏歌曲", 48, 20, false, () -> updatePlayerFavorite(toggleLike(entry)));
            lyricTrack.addView(lyricFavorite);
            space(lyricTrack, 5);
            updatePlayerFavorite(liked(entry));
        }
        lyricTrack.addView(playerIcon(UiIcon.Kind.ELLIPSIS, "播放器菜单", 48, 20, false, () -> showPlayerMenu(player)));
        body.addView(lyricTrack, body.indexOfChild(playerStage), new LinearLayout.LayoutParams(-1, -2));
        var scroll = new ScrollView(this);
        lyricsScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(30));
        if (android.os.Build.VERSION.SDK_INT >= 31 && lyricBlur == null) {
            lyricBlur = android.graphics.RenderEffect.createBlurEffect(dp(1.3f), dp(1.3f), android.graphics.Shader.TileMode.DECAL);
        }
        scroll.setOnClickListener(view -> toggleFullScreenLyrics());
        scroll.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                var click = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK;
                info.removeAction(click);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(click.getId(),
                    fullScreenLyrics() ? "退出全屏歌词" : "全屏显示歌词"));
            }
        });
        var gestures = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(android.view.MotionEvent event) { return scroll.performClick(); }
            @Override public boolean onScroll(android.view.MotionEvent start, android.view.MotionEvent end, float dx, float dy) {
                lyricManualUntil = android.os.SystemClock.elapsedRealtime() + 4500;
                return true;
            }
        });
        gestures.setOnDoubleTapListener(null);
        scroll.setOnTouchListener((view, event) -> {
            gestures.onTouchEvent(event);
            return false;
        });
        lyricRows = column();
        lyricRows.setPadding(dp(3), dp(16), dp(3), dp(16));
        scroll.addView(lyricRows);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (scroll == lyricsScroll && bottom - top != oldBottom - oldTop && !playerLyrics.isEmpty()) {
                padLyrics(scroll.getHeight());
            }
        });
        playerStage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        lyricMessage("正在加载歌词…");
        int pageId = generation;
        int revision = player.revision();
        String cacheKey = "lyrics:" + entry.source() + ":" + entry.id()
            + (entry.source().equals("library") ? ":" + sources.navCacheKey() : "");
        var cache = mediaCache.bucket(false);
        long cacheRevision = cache.revision();
        ScriptRuntime.Callback complete = (value, error) -> {
            if (!current(pageId) || displayedPlayer != player || player.revision() != revision) return;
            if (error != null || !(value instanceof JSONObject result)) { lyricMessage("歌词暂不可用"); return; }
            playerLyrics = Lyrics.parse(result.optString("lyric"), result.optString("tlyric"));
            if (playerLyrics.isEmpty()) { lyricMessage("这首歌暂无歌词"); return; }
            byte[] data = result.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length <= 512 * 1024) io.execute(() -> cache.write(cacheKey, data, cacheRevision));
            renderLyrics(player);
        };
        work(() -> {
            byte[] stored = cache.read(cacheKey);
            if (stored != null) return new JSONObject(new String(stored, StandardCharsets.UTF_8));
            if (entry.source().equals("library")) return MediaSources.args("lyric", sources.navidrome().lyrics(entry));
            return null;
        }, (value, error) -> {
            if (!current(pageId) || player.revision() != revision) return;
            if (value != null || error != null) complete.complete(value, error);
            else sources.lyrics(entry, complete);
        });
    }

    private void lyricMessage(String value) {
        lyricRows.removeAllViews();
        TextView label = text(value, 15, playerMuted(), false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(8), dp(42), dp(8), dp(20));
        fill(lyricRows, label);
    }

    private void renderLyrics(PlaybackService player) {
        lyricRows.removeAllViews();
        lyricHighlight = -1;
        padLyrics(lyricsScroll.getHeight());
        float size = getResources().getConfiguration().screenWidthDp < 360 ? 26 : 31;
        for (Lyrics.Line line : playerLyrics) {
            var row = column();
            row.setPadding(dp(8), dp(13), dp(8), dp(13));
            TextView words = text(line.text(), size, playerInk(), true);
            words.setLineSpacing(dp(4), 1f);
            fill(row, words);
            if (!line.translation().isEmpty()) {
                gap(row, 5);
                fill(row, text(line.translation(), 17, playerMuted(), true));
            }
            row.setAlpha(line.time() < 0 ? .92f : .56f);
            fill(lyricRows, row);
        }
        updateLyrics(player.position());
    }

    private void padLyrics(int height) {
        int padding = Math.max(dp(12), (int) (height * .27f));
        lyricRows.setPadding(dp(5), padding, dp(5), padding);
    }

    private void updateLyrics(int position) {
        if (!lyricsVisible() || lyricsScroll == null || playerLyrics.isEmpty()) return;
        int next = Lyrics.current(playerLyrics, position);
        if (lyricHighlight != next) {
            int previous = lyricHighlight;
            lyricHighlight = next;
            if (next >= 0) for (int i = 0; i < lyricRows.getChildCount(); i++) {
                int distance = Math.abs(i - next);
                if (previous >= 0 && distance > 7 && Math.abs(i - previous) > 7) continue;
                View row = lyricRows.getChildAt(i);
                row.setAlpha(distance == 0 ? 1f : distance == 1 ? .72f : .48f);
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    row.setRenderEffect(distance >= 3 && distance <= 6 ? lyricBlur : null);
                }
            }
        }
        if (next < 0 || lyricManualUntil > android.os.SystemClock.elapsedRealtime()) return;
        ScrollView scroller = lyricsScroll;
        View selected = lyricRows.getChildAt(next);
        int top = Math.max(0, selected.getTop() - scroller.getHeight() / 4);
        int bottom = Math.max(0, lyricRows.getHeight() - scroller.getHeight());
        top = Math.min(top, bottom);
        if (scroller.getHeight() > 0 && Math.abs(scroller.getScrollY() - top) > dp(6)) scroller.smoothScrollTo(0, top);
    }

    private TextView playerButton(String label, String description, Runnable action) {
        TextView button = text(label, 14, playerInk(), true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setContentDescription(description);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(playerTouch()), playerSurface(PILL_RADIUS), shape(Color.WHITE, PILL_RADIUS)));
        action(button, action);
        return button;
    }

    private GradientDrawable sheetSurface(boolean translucent) {
        boolean dark = playerPage ? playerDark() : darkAppearance;
        var surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            dark ? translucent ? new int[]{0xf02b2c39, 0xe91e202c} : new int[]{0xff2b2c39, 0xff1e202c}
                : translucent ? new int[]{0xf0fcfafe, 0xe9f0eaf3} : new int[]{0xfffcfafe, 0xfff0eaf3});
        float corner = dp(SHEET_RADIUS);
        surface.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        return surface;
    }

    private void showBottomSheet(android.app.Dialog dialog, View sheet, float heightFraction) {
        sheet.setBackground(sheetSurface(false));
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        var window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.44f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            var manager = getSystemService(WindowManager.class);
            View decor = window.getDecorView();
            java.util.function.Consumer<Boolean> updateBlur = enabled -> {
                if (!decor.isAttachedToWindow()) return;
                var params = window.getAttributes();
                params.setBlurBehindRadius(enabled ? dp(20) : 0);
                window.setAttributes(params);
                window.setDimAmount(enabled ? .29f : .44f);
                sheet.setBackground(sheetSurface(enabled));
            };
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { manager.addCrossWindowBlurEnabledListener(updateBlur); }
                @Override public void onViewDetachedFromWindow(View view) { manager.removeCrossWindowBlurEnabledListener(updateBlur); }
            });
            if (decor.isAttachedToWindow()) manager.addCrossWindowBlurEnabledListener(updateBlur);
        }
        window.setGravity(Gravity.BOTTOM);
        window.setVolumeControlStream(AudioManager.STREAM_MUSIC);
        var bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        window.setLayout(Math.min(bounds.width(), dp(580)), Math.min(dp(586), Math.round(bounds.height() * heightFraction)));
    }

    private void showQueue(PlaybackService player) {
        if (queueDialog != null) queueDialog.dismiss();
        android.app.Dialog dialog = new android.app.Dialog(this);
        queueDialog = dialog;
        var sheet = column();
        sheet.setPadding(dp(16), dp(12), dp(16), dp(12));
        var header = line();
        queueHeading = text("播放队列", 20, INK, true);
        weight(header, queueHeading);
        header.addView(iconButton(UiIcon.Kind.CLOSE, "关闭播放队列", false, dialog::dismiss));
        fill(sheet, header);
        gap(sheet, 10);
        var scroll = new ScrollView(this);
        queueRows = column();
        scroll.addView(queueRows);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(sheet);
        dialog.setOnDismissListener(ignored -> { if (queueDialog == dialog) { queueDialog = null; queueRows = null; } });
        showBottomSheet(dialog, sheet, .64f);
        renderQueue(player);
        scroll.post(() -> {
            View current = queueRows == null ? null : queueRows.getChildAt(player.index());
            if (current != null) scroll.scrollTo(0, current.getTop() - dp(18));
        });
    }

    private void renderQueue(PlaybackService player) {
        if (queueRows == null) return;
        sheetVersion = player.queueVersion();
        sheetIndex = player.index();
        queueHeading.setText("播放队列  ·  " + player.entries().size());
        queueRows.removeAllViews();
        List<MediaEntry> entries = player.entries();
        for (int i = 0; i < entries.size(); i++) {
            int index = i;
            MediaEntry entry = entries.get(i);
            boolean current = index == player.index();
            var item = line();
            item.setPadding(dp(10), dp(4), dp(5), dp(4));
            item.setBackground(shape(current ? ACCENT_TINT : PAPER, CARD_RADIUS));
            TextView info = text((current ? "♫   " : (index + 1) + "   ") + entry.title()
                + (entry.subtitle().isEmpty() ? "" : "\n" + entry.subtitle()), 14, current ? ACCENT : INK, current);
            info.setMaxLines(2);
            info.setEllipsize(TextUtils.TruncateAt.END);
            info.setPadding(dp(4), dp(9), dp(4), dp(9));
            info.setOnClickListener(view -> {
                if (!current) player.select(index);
                if (queueDialog != null) queueDialog.dismiss();
            });
            weight(item, info);
            if (!current) {
                var remove = iconButton(UiIcon.Kind.CLOSE, "从队列移除 " + entry.title(), false,
                    () -> { if (player.remove(index)) renderQueue(player); });
                item.addView(remove);
            }
            var placement = new LinearLayout.LayoutParams(-1, -2);
            placement.bottomMargin = dp(5);
            queueRows.addView(item, placement);
        }
    }
}
