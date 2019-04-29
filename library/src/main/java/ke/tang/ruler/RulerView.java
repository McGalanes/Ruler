/*
 * Copyright (C) 2018 TangKe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ke.tang.ruler;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.ColorInt;
import android.support.annotation.ColorRes;
import android.support.annotation.DrawableRes;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RulerView extends View {
    public final static int MAX_VALUE = 10000;
    private final static int STATE_IDLE = 0;
    private final static int STATE_PINCH = 1;
    private final static int STATE_SCROLL = 2;
    private final static int STATE_FLING = 3;
    private final static int STATE_RESET = 4;
    private int mStepWidth;
    private RulerValueFormatter mRulerValueFormatter;
    private ColorStateList mScaleColor;
    private ColorStateList mRulerColor;
    private ColorStateList mCircleColor;
    private int mSectionScaleCount;
    private Drawable mIndicator;
    private int mScaleMinHeight;
    private int mScaleMaxHeight;
    private int mScaleSize;
    private int mRulerSize;

    @IntRange(from = 0, to = MAX_VALUE)
    private int mMaxValue;
    @IntRange(from = 0, to = MAX_VALUE)
    private int mMinValue;
    @IntRange(from = 0, to = MAX_VALUE)
    private int mValue;

    private float mTextSize;
    private ColorStateList mTextColor;
    private OnRulerValueChangeListener mOnRulerValueChangeListener;
    private OverScroller mScroller;
    private int mContentOffset;
    private int mMaxContentOffset;
    private int mMinContentOffset;
    private Paint mRulerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint mScaleLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Paint mCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mLastX;
    private float mDownX;
    private float mDownDistance;
    private float mLastDistance;

    private Paint.FontMetrics mFontMetrics;
    private VelocityTracker mVelocityTracker = VelocityTracker.obtain();

    private int mState = STATE_IDLE;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mTouchSlop;
    private List<Marker> mMarkers = new ArrayList<>();
    private Comparator<Marker> mMarkerComparator = new Comparator<Marker>() {
        @Override
        public int compare(Marker o1, Marker o2) {
            int value1 = o1.value();
            int value2 = o2.value();
            if (value1 > value2) {
                return 1;
            } else if (value1 < value2) {
                return -1;
            } else {
                return 0;
            }
        }
    };

    private Rect mTempRect = new Rect();
    private RectF mTempRectF = new RectF();
    private int mMarkerHeight;

    public RulerView(Context context) {
        this(context, null);
    }

    public RulerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, R.attr.rulerViewStyle);
    }

    public RulerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mScroller = new OverScroller(context);
        mScroller.setFriction(0.005f);
        setWillNotDraw(false);

        mMinimumVelocity = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        mMaximumVelocity = ViewConfiguration.get(context).getScaledMaximumFlingVelocity();
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        final Resources resources = context.getResources();
        final DisplayMetrics displayMetrics = resources.getDisplayMetrics();
        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.RulerView, defStyleAttr, R.style.Widget_RulerView);
        mStepWidth = a.getDimensionPixelOffset(R.styleable.RulerView_stepWidth, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, displayMetrics));

        String valueFormatterClassName = a.getString(R.styleable.RulerView_rulerValueFormatter);
        if (!TextUtils.isEmpty(valueFormatterClassName)) {
            try {
                Class valueFormatterClass = Class.forName(valueFormatterClassName);
                if (!RulerValueFormatter.class.isAssignableFrom(valueFormatterClass)) {
                    throw new IllegalArgumentException(valueFormatterClassName + "类必须实现RulerValueFormatter");
                }
                Constructor constructor = valueFormatterClass.getConstructor();
                mRulerValueFormatter = (RulerValueFormatter) constructor.newInstance();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(valueFormatterClassName + "类必须包含默认构造函数");
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            }
        }


        setScaleColor(a.getColor(R.styleable.RulerView_scaleColor, Color.BLACK));
        setRulerColor(a.getColor(R.styleable.RulerView_rulerColor, Color.BLACK));
        setCircleColor(a.getColor(R.styleable.RulerView_circleColor, Color.RED));

        mSectionScaleCount = a.getInt(R.styleable.RulerView_sectionScaleCount, 10);
        mIndicator = a.getDrawable(R.styleable.RulerView_indicator);
        mScaleMinHeight = a.getDimensionPixelSize(R.styleable.RulerView_scaleMinHeight, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, displayMetrics));
        mScaleMaxHeight = a.getDimensionPixelSize(R.styleable.RulerView_scaleMaxHeight, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, displayMetrics));

        mRulerSize = a.getDimensionPixelSize(R.styleable.RulerView_rulerSize, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, displayMetrics));
        mScaleSize = a.getDimensionPixelSize(R.styleable.RulerView_scaleSize, (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, displayMetrics));
        mMaxValue = a.getInt(R.styleable.RulerView_maxValue, MAX_VALUE);
        mMinValue = a.getInt(R.styleable.RulerView_minValue, 0);
        if (mMaxValue < mMinValue) {
            throw new IllegalArgumentException("最大值不能小于最小值");
        }
        mValue = a.getInt(R.styleable.RulerView_value, mMinValue);
        if (mValue > mMaxValue || mValue < mMinValue) {
            throw new IllegalArgumentException("值需要介于最小值(" + mMinValue + ")和最大值(" + mMaxValue + ")之间");
        }

        mScaleLabelPaint.setTextAlign(Paint.Align.CENTER);
        setTextSize(a.getDimension(R.styleable.RulerView_android_textSize, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, displayMetrics)));
        setTextColor(a.getColorStateList(R.styleable.RulerView_android_textColor));
        setValue(mValue);

        a.recycle();
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mContentOffset = mScroller.getCurrX();
            mValue = getValueForContentOffset(mContentOffset);
            notifyValueChanged();
            invalidate();
        } else {
            if (needScrollToRoundValuePosition()) {
                scrollToRoundedValue();
            } else if (STATE_FLING == mState || STATE_RESET == mState) {
                mState = STATE_IDLE;
            }
        }
    }

    private void notifyValueChanged() {
        if (null != mOnRulerValueChangeListener) {
            mOnRulerValueChangeListener.onRulerValueChanged(mValue, null != mRulerValueFormatter ? mRulerValueFormatter.formatValue(mValue) : String.valueOf(mValue));
        }
    }

    private void scrollToRoundedValue() {
        int roundedValue = getRoundedValue(mContentOffset);
        mScroller.abortAnimation();
        mScroller.startScroll(mContentOffset, 0, getContentOffsetForValue(roundedValue) - mContentOffset, 0, 800);
        invalidate();
    }

    private int getRoundedValue(int offset) {
        return Math.max(mMinValue, Math.min(Math.round(offset * 1.0f / mStepWidth), mMaxValue));
    }

    private boolean needScrollToRoundValuePosition() {
        float currentValue = mContentOffset * 1.0f / mStepWidth;
        int roundedValue = Math.round(currentValue);
        return currentValue != roundedValue && (STATE_RESET == mState || STATE_FLING == mState);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int targetHeight = 0;
        targetHeight += mRulerSize; //横线高度
        targetHeight += Math.max(mScaleMaxHeight, mScaleMinHeight);
        targetHeight += mFontMetrics.bottom - mFontMetrics.top;
        if (null != mIndicator) {
            targetHeight = Math.max(mIndicator.getIntrinsicHeight(), targetHeight);
        }
        targetHeight += getPaddingTop() + getPaddingBottom();
        if (!mMarkers.isEmpty()) {
            int maxMarkerHeight = 0;
            for (Marker marker : mMarkers) {
                marker.getBounds(mTempRect);
                maxMarkerHeight = Math.max(maxMarkerHeight, mTempRect.height());
            }
            targetHeight += maxMarkerHeight;
            mMarkerHeight = maxMarkerHeight;
        }

        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth() + getPaddingLeft() + getPaddingRight(), widthMeasureSpec), resolveSize(targetHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int contentOffset = mContentOffset;
        final int paddingLeft = getPaddingLeft();
        final int paddingRight = getPaddingRight();
        final int paddingTop = getPaddingTop();
        final int width = getWidth();
        final int height = getHeight();
        final int insetWidth = width - paddingLeft - paddingRight;
        final int halfInsetWidth = insetWidth / 2;
        final float scaleSize = mScaleSize;
        final int maxScaleCount = mMaxValue;
        final int minScaleCount = mMinValue;
        final int[] drawableState = getDrawableState();

        mRulerPaint.setColor(mScaleColor.isStateful() ? mScaleColor.getColorForState(drawableState, Color.BLACK) : mScaleColor.getDefaultColor());

        mScaleLabelPaint.setTextAlign(Paint.Align.CENTER);

        //Draw scales forward and draw to the left border to stop
        if (null != mTextColor) {
            mScaleLabelPaint.setColor(mTextColor.getColorForState(drawableState, Color.BLACK));
        }

        //Draw indicator
        final Drawable indicator = ContextCompat.getDrawable(getContext(), R.drawable.ic_cursor);
        mIndicator = indicator;
        if (indicator.isStateful()) {
            indicator.setState(drawableState);
        }
        indicator.setBounds(
                paddingLeft + halfInsetWidth - mIndicator.getIntrinsicWidth() / 2,
                paddingTop,
                paddingLeft + halfInsetWidth + mIndicator.getIntrinsicWidth() / 2,
                indicator.getIntrinsicHeight()
        );
        indicator.draw(canvas);


        final float fontY = (height - mIndicator.getIntrinsicHeight()) / 2f - ((mScaleLabelPaint.descent() + mScaleLabelPaint.ascent()) / 2f) + mIndicator.getIntrinsicHeight();
        int count = contentOffset / mStepWidth;
        for (int index = Math.min(count, maxScaleCount); index >= minScaleCount; index--) {
            int scalePosition = index * mStepWidth;
            final float centerX = paddingLeft + halfInsetWidth + scalePosition - contentOffset;
            final float left = centerX - scaleSize / 2;
            final float right = centerX + scaleSize / 2;
            String label = null != mRulerValueFormatter ? mRulerValueFormatter.formatValue(index) : String.valueOf(index);
            final float labelRight = centerX + mScaleLabelPaint.measureText(label) / 2;

            if (labelRight > 0) {
                if (0 == index % mSectionScaleCount || index == maxScaleCount || index == minScaleCount) {
                    canvas.drawRect(left, 0, right, mScaleMaxHeight, mRulerPaint);
                    canvas.drawText(label, centerX, fontY, mScaleLabelPaint);
                } else {
                    canvas.drawRect(left, 0, right, mScaleMinHeight, mRulerPaint);
                }
            } else {
                break;
            }
        }

        //Draw scales backwards, draw from marker to the right
        for (int index = Math.max(minScaleCount, count); index <= maxScaleCount; index++) {
            int scalePosition = index * mStepWidth;
            final float centerX = paddingLeft + halfInsetWidth + scalePosition - contentOffset;
            final float left = centerX - scaleSize / 2;
            final float right = centerX + scaleSize / 2;
            String label = null != mRulerValueFormatter ? mRulerValueFormatter.formatValue(index) : String.valueOf(index);
            final float labelLeft = centerX - mScaleLabelPaint.measureText(label) / 2;
            if (labelLeft < width) {
                if (0 == index % mSectionScaleCount || index == maxScaleCount || index == minScaleCount) {
                    canvas.drawRect(left, 0, right, mScaleMaxHeight, mRulerPaint);
                    canvas.drawText(label, centerX, fontY, mScaleLabelPaint);
                } else {
                    canvas.drawRect(left, 0, right, mScaleMinHeight, mRulerPaint);
                }
            } else {
                break;
            }
        }

        //Drawing Marker
        if (!mMarkers.isEmpty()) {
            for (Marker marker : mMarkers) {
                int scalePosition = marker.value() * mStepWidth;
                marker.getBounds(mTempRect);
                final float centerX = paddingLeft + halfInsetWidth + scalePosition - contentOffset;
                final float left = centerX - mTempRect.width() / 2f;
                final float right = centerX + mTempRect.width() / 2f;
                final float x = left, y = height - mMarkerHeight;
                marker.setX(x);
                marker.setY(y);
                if (right > 0 || left < width) {
                    canvas.save();
                    canvas.translate(x, y);
                    marker.onDraw(canvas);
                    canvas.restore();
                }
            }
        }

        //Drawing Circle
        mCirclePaint.setColor(mCircleColor.isStateful() ? mCircleColor.getColorForState(drawableState, Color.BLACK) : mCircleColor.getDefaultColor());

        float circleY = (height - mIndicator.getIntrinsicHeight()) / 2f + mIndicator.getIntrinsicHeight();
        float topAndBottomCirclePadding = 8;
        float radius = (height - mIndicator.getIntrinsicHeight()) / 2f - 2f * topAndBottomCirclePadding;
        canvas.drawCircle(halfInsetWidth, circleY, radius, mCirclePaint);
    }

    private int getValueForContentOffset(int contentOffset) {
        return Math.max(Math.min(Math.round(contentOffset * 1.0f / mStepWidth), mMaxValue), mMinValue);
    }

    private int getContentOffsetForValue(int relativeValue) {
        try {
            return MathUtils.multiplyExact(relativeValue, mStepWidth);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float x = event.getX();
        final float y = event.getY();
        int pointerCount = event.getPointerCount();
        int width = getWidth();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetStateAndAbortScroll();
                mDownX = x;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerCount > 1) {
                    mDownDistance = getMaxDistanceOfPointers(event);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                break;
            case MotionEvent.ACTION_MOVE:
                if (pointerCount > 1) {
                    float currentDistance = getMaxDistanceOfPointers(event);
                    if (STATE_PINCH != mState && STATE_SCROLL != mState) {
                        if (Math.abs(currentDistance - mDownDistance) > mTouchSlop) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                            mState = STATE_PINCH;
                        }
                    }
                    if (STATE_PINCH == mState) {
                        float dDistance = currentDistance - mLastDistance;
                        mStepWidth = Math.max(1, (int) (mStepWidth + dDistance / 2));
                        mValue = Math.max(mMinValue, Math.min(mValue, mMaxValue));
                        mContentOffset = getContentOffsetForValue(mValue);
                        mMaxContentOffset = getContentOffsetForValue(mMaxValue);
                        mMinContentOffset = getContentOffsetForValue(mMinValue);
                        invalidate();
                    }
                    mLastDistance = currentDistance;
                } else {
                    float dx = x - mLastX;
                    if (STATE_SCROLL != mState && STATE_PINCH != mState) {
                        if (Math.abs(x - mDownX) > mTouchSlop) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                            mState = STATE_SCROLL;
                        }
                    }
                    if (STATE_SCROLL == mState) {
                        if (mContentOffset - dx < mMinContentOffset || mContentOffset - dx > mMaxContentOffset) {
                            dx = dx / 2;
                        }
                        mContentOffset -= dx;
                        mValue = getValueForContentOffset(mContentOffset);
                        notifyValueChanged();
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                switch (mState) {
                    case STATE_IDLE:
                        if (!mMarkers.isEmpty()) {
                            for (Marker marker : mMarkers) {
                                marker.getBounds(mTempRect);
                                mTempRectF.set(mTempRect);
                                mTempRectF.offset(marker.getX(), marker.getY());
                                if (mTempRectF.contains(x, y)) {
                                    marker.performClick();
                                    //只触发一个
                                    break;
                                }
                            }
                        }
                    case STATE_PINCH:
                        mState = STATE_RESET;
                        scrollToRoundedValue();
                        break;
                    default:
                    case STATE_FLING:
                    case STATE_RESET:
                    case STATE_SCROLL:
                        mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                        float velocityX = mVelocityTracker.getXVelocity();
                        if (mContentOffset < mMinContentOffset) {
                            mState = STATE_RESET;
                            mScroller.springBack(mContentOffset, 0, mMinContentOffset, mMaxContentOffset, 0, 0);
                        } else if (mContentOffset > mMaxContentOffset) {
                            mState = STATE_RESET;
                            mScroller.springBack(mContentOffset, 0, mMinContentOffset, mMaxContentOffset, 0, 0);
                        } else if (Math.abs(velocityX) > mMinimumVelocity) {
                            mState = STATE_FLING;
                            int resolvedVelocityX = (int) -velocityX;

                            //矫正Fling速度，让最后始终停留在我具体的刻度上
                            int flingOffset = (int) mScroller.getSplineFlingDistance(resolvedVelocityX);
                            int targetOffset = mContentOffset + flingOffset;
                            if (targetOffset >= mMinContentOffset && targetOffset <= mMaxContentOffset) {
                                resolvedVelocityX = mScroller.getSplineFlingVelocity(getContentOffsetForValue(getValueForContentOffset(targetOffset)) - mContentOffset);
                            }
                            mScroller.fling(mContentOffset, 0, resolvedVelocityX, 0, mMinContentOffset, mMaxContentOffset, 0, 0, (int) (width / 8f), 0);
                        } else {
                            mState = STATE_RESET;
                            scrollToRoundedValue();
                        }
                        break;
                }
                invalidate();
            case MotionEvent.ACTION_CANCEL:
                mVelocityTracker.clear();
                break;
        }
        mVelocityTracker.addMovement(event);
        mLastX = event.getX();
        return true;
    }

    private float getMaxDistanceOfPointers(MotionEvent event) {
        final int pointerCount = event.getPointerCount();
        float maxX = 0, minX = 0;
        for (int index = 0; index < pointerCount; index++) {
            float currentX = event.getX(index);
            maxX = Math.max(currentX, maxX);
            minX = Math.min(currentX, minX);
        }
        return Math.abs(maxX - minX);
    }

    public void setRulerValueFormatter(RulerValueFormatter rulerValueFormatter) {
        mRulerValueFormatter = rulerValueFormatter;
        notifyValueChanged();
        invalidate();
    }

    private void resetStateAndAbortScroll() {
        mState = STATE_IDLE;
        mScroller.abortAnimation();
    }

    public int getValue() {
        return mValue;
    }

    public void setValue(@IntRange(from = 0, to = MAX_VALUE) int value) {
        mValue = Math.max(mMinValue, Math.min(value, mMaxValue));
        mContentOffset = getContentOffsetForValue(mValue);
        mMaxContentOffset = getContentOffsetForValue(mMaxValue);
        mMinContentOffset = getContentOffsetForValue(mMinValue);
        resetStateAndAbortScroll();
        invalidate();
        notifyValueChanged();
    }

    public String getFormatValue() {
        return null != mRulerValueFormatter ? mRulerValueFormatter.formatValue(mValue) : String.valueOf(mValue);
    }

    public void setOnRulerValueChangeListener(OnRulerValueChangeListener onRulerValueChangeListener) {
        mOnRulerValueChangeListener = onRulerValueChangeListener;
    }

    public void setTextColorResource(@ColorRes int res) {
        setTextColor(0 != res ? getResources().getColorStateList(res) : ColorStateList.valueOf(Color.BLACK));
    }

    public void setRulerColorResource(@ColorRes int res) {
        setRulerColor(0 != res ? getResources().getColorStateList(res) : ColorStateList.valueOf(Color.BLACK));
    }

    public void setScaleColorResource(@ColorRes int res) {
        setScaleColor(0 != res ? getResources().getColorStateList(res) : ColorStateList.valueOf(Color.BLACK));
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getStepWidth() {
        return mStepWidth;
    }

    public void setStepWidth(int stepWidth) {
        mStepWidth = Math.max(1, stepWidth);
        setValue(mValue);
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public ColorStateList getScaleColor() {
        return mScaleColor;
    }

    public void setScaleColor(@ColorInt int color) {
        setScaleColor(ColorStateList.valueOf(color));
    }

    public void setScaleColor(ColorStateList color) {
        mScaleColor = color;
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public ColorStateList getRulerColor() {
        return mRulerColor;
    }

    public void setRulerColor(@ColorInt int color) {
        setRulerColor(ColorStateList.valueOf(color));
    }

    public void setRulerColor(ColorStateList color) {
        mRulerColor = color;
        invalidate();
    }

    public void setCircleColor(ColorStateList color) {
        mCircleColor = color;
        invalidate();
    }

    public void setCircleColor(@ColorInt int color) {
        setCircleColor(ColorStateList.valueOf(color));
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getSectionScaleCount() {
        return mSectionScaleCount;
    }

    public void setSectionScaleCount(int sectionScaleCount) {
        mSectionScaleCount = Math.max(0, sectionScaleCount);
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public Drawable getIndicator() {
        return mIndicator;
    }

    public void setIndicator(@DrawableRes int res) {
        setIndicator(0 != res ? getResources().getDrawable(res) : null);
    }

    public void setIndicator(Drawable indicator) {
        if (null != mIndicator) {
            mIndicator.setCallback(null);
        }
        mIndicator = indicator;
        if (null != indicator) {
            indicator.setCallback(this);
        }
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getScaleMinHeight() {
        return mScaleMinHeight;
    }

    public void setScaleMinHeight(int scaleMinHeight) {
        mScaleMinHeight = scaleMinHeight;
        requestLayout();
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getScaleMaxHeight() {
        return mScaleMaxHeight;
    }

    public void setScaleMaxHeight(int scaleMaxHeight) {
        mScaleMaxHeight = scaleMaxHeight;
        requestLayout();
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getScaleSize() {
        return mScaleSize;
    }

    public void setScaleSize(int scaleSize) {
        mScaleSize = Math.max(0, scaleSize);
        requestLayout();
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getRulerSize() {
        return mRulerSize;
    }

    public void setRulerSize(int rulerSize) {
        mRulerSize = Math.max(0, rulerSize);
        requestLayout();
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getMaxValue() {
        return mMaxValue;
    }

    public void setMaxValue(@IntRange(from = 0, to = MAX_VALUE) int maxValue) {
        if (maxValue < mMinValue) {
            throw new IllegalArgumentException("最大值: " + maxValue + " 不能小于最小值: " + mMinValue);
        }
        mMaxValue = maxValue;
        setValue(mValue);
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public int getMinValue() {
        return mMinValue;
    }

    public void setMinValue(@IntRange(from = 0, to = MAX_VALUE) int minValue) {
        if (minValue > mMaxValue) {
            throw new IllegalArgumentException("最小值: " + minValue + " 不能大于最大值: " + mMaxValue);
        }
        mMinValue = minValue;
        setValue(mValue);
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public float getTextSize() {
        return mTextSize;
    }

    public void setTextSize(float textSize) {
        mTextSize = textSize;
        mScaleLabelPaint.setTextSize(textSize);
        mFontMetrics = mScaleLabelPaint.getFontMetrics();
        requestLayout();
        invalidate();
    }

    @ViewDebug.ExportedProperty(category = "custom")
    public ColorStateList getTextColor() {
        return mTextColor;
    }

    public void setTextColor(@ColorInt int color) {
        setTextColor(ColorStateList.valueOf(color));
    }

    public void setTextColor(ColorStateList color) {
        mTextColor = color;
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who) || who == mIndicator;
    }

    public void addMarker(Marker marker) {
        mMarkers.add(marker);
        Collections.sort(mMarkers, mMarkerComparator);
        marker.onAttach(this);
        requestLayout();
        invalidate();
    }

    public void removeMarker(Marker marker) {
        mMarkers.remove(marker);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState savedState = (SavedState) state;
        super.onRestoreInstanceState(savedState.getSuperState());
        mStepWidth = savedState.mStepWidth;
        mScaleColor = savedState.mScaleColor;
        mRulerColor = savedState.mRulerColor;
        mCircleColor = savedState.mCircleColor;
        mSectionScaleCount = savedState.mSectionScaleCount;
        mScaleMinHeight = savedState.mScaleMinHeight;
        mScaleMaxHeight = savedState.mScaleMaxHeight;
        mScaleSize = savedState.mScaleSize;
        mRulerSize = savedState.mRulerSize;
        mMaxValue = savedState.mMaxValue;
        mMinValue = savedState.mMinValue;
        mValue = savedState.mValue;
        setTextSize(savedState.mTextSize);
        mTextColor = savedState.mTextColor;
        mState = savedState.mState;
        mContentOffset = savedState.mContentOffset;
        mMaxContentOffset = savedState.mMaxContentOffset;
        mMinContentOffset = savedState.mMinContentOffset;
        mMarkers = savedState.mMarkers;
        for (Marker marker : mMarkers) {
            marker.onAttach(this);
        }
    }

    @Nullable
    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState state = new SavedState(super.onSaveInstanceState());
        state.mStepWidth = mStepWidth;
        state.mScaleColor = mScaleColor;
        state.mRulerColor = mRulerColor;
        state.mCircleColor = mCircleColor;
        state.mSectionScaleCount = mSectionScaleCount;
        state.mScaleMinHeight = mScaleMinHeight;
        state.mScaleMaxHeight = mScaleMaxHeight;
        state.mScaleSize = mScaleSize;
        state.mRulerSize = mRulerSize;
        state.mMaxValue = mMaxValue;
        state.mMinValue = mMinValue;
        state.mValue = mValue;
        state.mTextSize = mTextSize;
        state.mTextColor = mTextColor;
        state.mState = mState;
        state.mContentOffset = mContentOffset;
        state.mMaxContentOffset = mMaxContentOffset;
        state.mMinContentOffset = mMinContentOffset;
        state.mMarkers = mMarkers;
        return state;
    }

    private static class SavedState extends BaseSavedState {
        public static final Creator<SavedState> CREATOR = new Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
        private int mStepWidth;
        private ColorStateList mScaleColor;
        private ColorStateList mRulerColor;
        private ColorStateList mCircleColor;
        private int mSectionScaleCount;
        private int mScaleMinHeight;
        private int mScaleMaxHeight;
        private int mScaleSize;
        private int mRulerSize;
        private int mMaxValue;
        private int mMinValue;
        private int mValue;
        private float mTextSize;
        private ColorStateList mTextColor;
        private int mState;
        private int mContentOffset;
        private int mMaxContentOffset;
        private int mMinContentOffset;
        private List<Marker> mMarkers;

        public SavedState(Parcel source) {
            super(source);
            mStepWidth = source.readInt();
            mScaleColor = source.readParcelable(ColorStateList.class.getClassLoader());
            mRulerColor = source.readParcelable(ColorStateList.class.getClassLoader());
            mCircleColor = source.readParcelable(ColorStateList.class.getClassLoader());
            mSectionScaleCount = source.readInt();
            mScaleMinHeight = source.readInt();
            mScaleMaxHeight = source.readInt();
            mScaleSize = source.readInt();
            mRulerSize = source.readInt();
            mMaxValue = source.readInt();
            mMinValue = source.readInt();
            mValue = source.readInt();
            mTextSize = source.readFloat();
            mTextColor = source.readParcelable(ColorStateList.class.getClassLoader());
            mState = source.readInt();
            mContentOffset = source.readInt();
            mMaxContentOffset = source.readInt();
            mMinContentOffset = source.readInt();
            mMarkers = source.readArrayList(Marker.class.getClassLoader());
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(mStepWidth);
            out.writeParcelable(mScaleColor, 0);
            out.writeParcelable(mRulerColor, 0);
            out.writeParcelable(mCircleColor, 0);
            out.writeInt(mSectionScaleCount);
            out.writeInt(mScaleMinHeight);
            out.writeInt(mScaleMaxHeight);
            out.writeInt(mScaleSize);
            out.writeInt(mRulerSize);
            out.writeInt(mMaxValue);
            out.writeInt(mMinValue);
            out.writeInt(mValue);
            out.writeFloat(mTextSize);
            out.writeParcelable(mTextColor, 0);
            out.writeInt(mState);
            out.writeInt(mContentOffset);
            out.writeInt(mMaxContentOffset);
            out.writeInt(mMinContentOffset);
            out.writeList(mMarkers);
        }
    }
}
