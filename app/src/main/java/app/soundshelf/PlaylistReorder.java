package app.soundshelf;

import android.view.DragEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.function.BiConsumer;

/** Long-press drag within one playlist group, including scrolling at the page edges. */
final class PlaylistReorder {
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final BiConsumer<Integer, Integer> move;
    private final Runnable save;
    private final ArrayList<View> original = new ArrayList<>();
    private final int[] location = new int[2];
    private View dragged;
    private float screenY;
    private boolean inside;
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragged == null || !inside || scroll == null || !rows.isAttachedToWindow()) return;
            scroll.getLocationOnScreen(location);
            float y = screenY - location[1];
            int edge = Math.round(64 * rows.getResources().getDisplayMetrics().density);
            int step = Math.max(1, edge / 5);
            if (y < edge) scroll.scrollBy(0, -step);
            else if (y > scroll.getHeight() - edge) scroll.scrollBy(0, step);
            reposition();
            rows.postDelayed(this, 32);
        }
    };

    PlaylistReorder(LinearLayout rows, ScrollView scroll, BiConsumer<Integer, Integer> move, Runnable save) {
        this.rows = rows;
        this.scroll = scroll;
        this.move = move;
        this.save = save;
        rows.setOnDragListener((view, event) -> drag(event));
        rows.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { finish(false); }
        });
    }

    void bind(View wrapper, View touchTarget) {
        touchTarget.setOnLongClickListener(view -> {
            if (dragged != null || rows.getChildCount() < 2) return true;
            original.clear();
            for (int i = 0; i < rows.getChildCount(); i++) original.add(rows.getChildAt(i));
            dragged = wrapper;
            if (!view.startDragAndDrop(null, new View.DragShadowBuilder(view), this, 0)) {
                dragged = null;
                original.clear();
                return false;
            }
            wrapper.setAlpha(0.35f);
            return true;
        });
    }

    private boolean drag(DragEvent event) {
        if (event.getLocalState() != this) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return true;
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                if (dragged == null) return false;
                rows.getLocationOnScreen(location);
                screenY = location[1] + event.getY();
                inside = true;
                reposition();
                rows.removeCallbacks(autoScroll);
                rows.postDelayed(autoScroll, 32);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                inside = false;
                rows.removeCallbacks(autoScroll);
                return true;
            case DragEvent.ACTION_DROP:
                if (dragged == null) return false;
                rows.getLocationOnScreen(location);
                screenY = location[1] + event.getY();
                reposition();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                finish(event.getResult());
                return true;
            default: return true;
        }
    }

    private void reposition() {
        if (dragged == null) return;
        rows.getLocationOnScreen(location);
        float y = screenY - location[1];
        int from = rows.indexOfChild(dragged), to = 0;
        if (from < 0) return;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child != dragged && y > (child.getTop() + child.getBottom()) / 2f) to++;
        }
        if (from != to) {
            rows.removeView(dragged);
            rows.addView(dragged, to);
            move.accept(from, to);
        }
    }

    private void finish(boolean dropped) {
        rows.removeCallbacks(autoScroll);
        inside = false;
        if (dragged == null) return;
        dragged.setAlpha(1f);
        dragged = null;
        if (dropped) save.run();
        else {
            // Cancellation (including navigating away) restores both the views and their model.
            for (int to = 0; to < original.size(); to++) {
                View child = original.get(to);
                int from = rows.indexOfChild(child);
                if (from >= 0 && from != to) {
                    rows.removeView(child);
                    rows.addView(child, to);
                    move.accept(from, to);
                }
            }
        }
        original.clear();
    }
}
