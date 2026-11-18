package app.soundshelf;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import java.util.LinkedHashMap;

final class CoverViewport {
    private final ScrollView scroll;
    private final LinkedHashMap<View, Runnable> pending = new LinkedHashMap<>();
    private final Runnable resume = () -> { scrolling = false; schedule(); };
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::schedule;
    private boolean scheduled;
    private boolean scrolling;

    CoverViewport(ScrollView scroll, java.util.function.IntConsumer onScroll) {
        this.scroll = scroll;
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            if (y != oldY) onScroll.accept(y);
            scrolling = true;
            scroll.removeCallbacks(resume);
            scroll.postDelayed(resume, 90);
        });
        var listener = new View.OnAttachStateChangeListener() {
            private ViewTreeObserver observer;

            @Override public void onViewAttachedToWindow(View attached) {
                observer = attached.getViewTreeObserver();
                observer.addOnGlobalLayoutListener(layout);
            }

            @Override public void onViewDetachedFromWindow(View detached) {
                if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(layout);
                observer = null;
                scroll.removeCallbacks(resume);
                scrolling = false;
            }
        };
        scroll.addOnAttachStateChangeListener(listener);
        if (scroll.isAttachedToWindow()) listener.onViewAttachedToWindow(scroll);
    }

    void loadWhenVisible(View view, Runnable load) {
        var listener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) {
                pending.put(attached, () -> {
                    attached.removeOnAttachStateChangeListener(this);
                    load.run();
                });
                schedule();
            }
            @Override public void onViewDetachedFromWindow(View detached) {
                pending.remove(detached);
                detached.removeOnAttachStateChangeListener(this);
            }
        };
        view.addOnAttachStateChangeListener(listener);
        if (view.isAttachedToWindow()) listener.onViewAttachedToWindow(view);
    }

    void checkVisible() { schedule(); }

    private void schedule() {
        if (scheduled || scrolling || pending.isEmpty()) return;
        scheduled = true;
        scroll.postOnAnimation(() -> {
            scheduled = false;
            if (scrolling || !scroll.isAttachedToWindow()) return;
            var visible = new Rect();
            var ready = new java.util.ArrayList<Runnable>();
            var entries = pending.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (!entry.getKey().isShown() || !entry.getKey().getGlobalVisibleRect(visible)) continue;
                Runnable load = entry.getValue();
                entries.remove();
                ready.add(load);
            }
            for (Runnable load : ready) load.run();
        });
    }
}
