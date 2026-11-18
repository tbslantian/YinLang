package app.soundshelf;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

final class PlayerStage extends FrameLayout {
    private final int maxArtworkSize, touchSlop, swipeDistance;
    private View artwork;
    private boolean book;
    private Runnable onSwipe;
    private float downX, downY;
    private boolean horizontal, vertical, multiTouch;

    PlayerStage(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        maxArtworkSize = Math.round(350 * density);
        swipeDistance = Math.round(42 * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setArtwork(View view, boolean audiobook) {
        artwork = view;
        book = audiobook;
        addView(view, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
    }

    void setOnSwipe(Runnable action) {
        onSwipe = action;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (artwork != null) {
            int edge = Math.min(maxArtworkSize, Math.min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec)));
            var bounds = artwork.getLayoutParams();
            bounds.width = book ? Math.round(edge * .77f) : edge;
            bounds.height = book ? Math.round(edge * .94f) : edge;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (onSwipe == null) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                horizontal = vertical = multiTouch = false;
                super.dispatchTouchEvent(event);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!horizontal && !vertical && !multiTouch) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (dx > touchSlop && dx > dy * 1.25f) {
                        horizontal = true;
                        cancelChildGesture(event);
                    } else if (dy > touchSlop && dy >= dx) {
                        vertical = true;
                    }
                }
                if (horizontal && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (!multiTouch && Math.abs(event.getX() - downX) >= swipeDistance) onSwipe.run();
                    return true;
                }
                break;
            default: break;
        }
        if (!horizontal) super.dispatchTouchEvent(event);
        return true;
    }

    private void cancelChildGesture(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }
}
