/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.graalmail.android.R;
import com.graalmail.core.domain.Message;
import com.google.android.material.color.MaterialColors;

/**
 * Thunderbird-style swipe actions for message lists: swipe right toggles
 * read/unread, swipe left deletes. Actions are queued for synchronisation;
 * there is no undo because queued actions cannot be recalled once stored.
 */
final class MessageSwipeHelper extends ItemTouchHelper.SimpleCallback {

    /** Provides the message for an adapter position. */
    interface MessageAt {
        Message messageAt(int position);
    }

    /** Handles a completed swipe. */
    interface SwipeActions {
        void onSwipeRight(Message message);
        void onSwipeLeft(Message message);
        boolean isSwipeEnabled();
    }

    private final MessageAt messages;
    private final SwipeActions actions;
    private final Paint backgroundPaint = new Paint();
    private final Rect iconBounds = new Rect();

    MessageSwipeHelper(MessageAt messages, SwipeActions actions) {
        super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        this.messages = messages;
        this.actions = actions;
    }

    void attach(RecyclerView list) {
        new ItemTouchHelper(this).attachToRecyclerView(list);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder,
                          @NonNull RecyclerView.ViewHolder target) {
        return false;
    }

    @Override
    public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder) {
        if (!actions.isSwipeEnabled() || viewHolder.getBindingAdapterPosition() < 0) {
            return 0;
        }
        return super.getSwipeDirs(recyclerView, viewHolder);
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        int position = viewHolder.getBindingAdapterPosition();
        if (position < 0) return;
        Message message = messages.messageAt(position);
        if (message == null) return;
        if (direction == ItemTouchHelper.RIGHT) {
            actions.onSwipeRight(message);
        } else {
            actions.onSwipeLeft(message);
        }
    }

    @Override
    public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
                            int actionState, boolean isCurrentlyActive) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0) {
            drawSwipeBackground(canvas, viewHolder.itemView, dX);
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
    }

    private void drawSwipeBackground(Canvas canvas, View itemView, float dX) {
        Context context = itemView.getContext();
        boolean toRight = dX > 0;
        int background = MaterialColors.getColor(itemView, toRight
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorErrorContainer);
        int foreground = MaterialColors.getColor(itemView, toRight
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnErrorContainer);
        int icon = toRight ? android.R.drawable.ic_menu_edit : android.R.drawable.ic_menu_delete;
        backgroundPaint.setColor(background);
        if (toRight) {
            canvas.drawRect(itemView.getLeft(), itemView.getTop(),
                    itemView.getLeft() + dX, itemView.getBottom(), backgroundPaint);
        } else {
            canvas.drawRect(itemView.getRight() + dX, itemView.getTop(),
                    itemView.getRight(), itemView.getBottom(), backgroundPaint);
        }
        Drawable drawable = ContextCompat.getDrawable(context, icon);
        if (drawable == null) return;
        int size = Math.round(24 * context.getResources().getDisplayMetrics().density);
        int centerY = (itemView.getTop() + itemView.getBottom() - size) / 2;
        int left = toRight
                ? itemView.getLeft() + Math.round(20 * context.getResources().getDisplayMetrics().density)
                : Math.round(itemView.getRight() + dX
                        - 20 * context.getResources().getDisplayMetrics().density - size);
        iconBounds.set(left, centerY, left + size, centerY + size);
        drawable.setBounds(iconBounds);
        drawable.setTint(foreground);
        drawable.draw(canvas);
    }
}
