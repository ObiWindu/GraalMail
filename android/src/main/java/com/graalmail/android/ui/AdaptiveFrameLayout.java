/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.content.res.TypedArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.graalmail.android.R;

/** Centers one child and caps its width on large screens while filling phones. */
public final class AdaptiveFrameLayout extends FrameLayout {
    private int contentMaxWidth;

    public AdaptiveFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public AdaptiveFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        int defaultWidth = getResources().getDimensionPixelSize(R.dimen.content_max_width);
        TypedArray values = context.obtainStyledAttributes(attrs, R.styleable.AdaptiveFrameLayout,
                defStyleAttr, 0);
        contentMaxWidth = values.getDimensionPixelSize(
                R.styleable.AdaptiveFrameLayout_contentMaxWidth, defaultWidth);
        values.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int horizontalPadding = getPaddingLeft() + getPaddingRight();
        int innerAvailableWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? contentMaxWidth : Math.max(0, availableWidth - horizontalPadding);
        int childWidth = Math.min(contentMaxWidth, innerAvailableWidth);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
        int childHeightSpec = getChildMeasureSpec(heightMeasureSpec,
                getPaddingTop() + getPaddingBottom(), ViewGroup.LayoutParams.MATCH_PARENT);

        View child = getChildCount() == 0 ? null : getChildAt(0);
        if (child != null && child.getVisibility() != GONE) {
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            int margins = 0;
            if (raw instanceof MarginLayoutParams margin) {
                margins = margin.leftMargin + margin.rightMargin;
            }
            int measuredChildWidth = Math.max(0, childWidth - margins);
            int childWidthSpec = MeasureSpec.makeMeasureSpec(measuredChildWidth, MeasureSpec.EXACTLY);
            child.measure(childWidthSpec, childHeightSpec);
            parentHeight = child.getMeasuredHeight() + getPaddingTop() + getPaddingBottom();
        }

        int measuredWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? childWidth + horizontalPadding : availableWidth;
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec),
                resolveSize(parentHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View child = getChildCount() == 0 ? null : getChildAt(0);
        if (child == null || child.getVisibility() == GONE) return;
        ViewGroup.LayoutParams raw = child.getLayoutParams();
        int marginLeft = raw instanceof MarginLayoutParams margin ? margin.leftMargin : 0;
        int marginTop = raw instanceof MarginLayoutParams margin ? margin.topMargin : 0;
        int childLeft = getPaddingLeft() + Math.max(0, (getMeasuredWidth() - getPaddingLeft()
                - getPaddingRight() - child.getMeasuredWidth()) / 2) + marginLeft;
        int childTop = getPaddingTop() + marginTop;
        child.layout(childLeft, childTop, childLeft + child.getMeasuredWidth(),
                childTop + child.getMeasuredHeight());
    }
}
