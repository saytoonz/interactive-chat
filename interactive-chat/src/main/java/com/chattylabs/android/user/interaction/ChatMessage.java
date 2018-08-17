package com.chattylabs.android.user.interaction;

import android.support.annotation.ColorRes;
import android.support.annotation.DrawableRes;

import java.util.Objects;

public class ChatMessage implements ChatNode {
    public final String id;
    public final String text;
    public final int imageId;
    public final int tintColorId;
    public final boolean aloud;
    public final boolean shownAsHead;
    public final boolean shownAsAction;
    public final Runnable onLoaded;
    public final float textSize;

    public static class Builder {
        private String id;
        private String text;
        private int imageId;
        private int tintColorId;
        private boolean aloud;
        private boolean shownAsHead;
        private boolean shownAsAction;
        private Runnable onLoaded;
        private float textSize;

        public Builder() {
        }

        public Builder(String id) {
            this.id = id;
        }

        public Builder setText(String text) {
            this.text = text;
            return this;
        }

        public Builder setImage(@DrawableRes int resId) {
            this.imageId = resId;
            return this;
        }

        public Builder setTintColor(@ColorRes int resId) {
            this.tintColorId = resId;
            return this;
        }

        public Builder setAloud(boolean aloud) {
            this.aloud = aloud;
            return this;
        }

        public Builder setShownAsHead(boolean shownAsHead) {
            this.shownAsHead = shownAsHead;
            return this;
        }

        public Builder setShownAsAction(boolean shownAsAction) {
            this.shownAsAction = shownAsAction;
            return this;
        }

        public Builder setOnLoaded(Runnable afterLoaded) {
            this.onLoaded = afterLoaded;
            return this;
        }

        public Builder setTextSize(float textSizeInSp) {
            this.textSize = textSizeInSp;
            return this;
        }

        public ChatMessage build() {
            if (!(text != null && text.length() > 0) && imageId <= 0) {
                throw new NullPointerException("At least Message properties \"text\" " +
                        "or \"image\" must be set");
            }
            return new ChatMessage(this);
        }
    }

    private ChatMessage(Builder builder) {
        this.id = builder.id;
        this.text = builder.text;
        this.imageId = builder.imageId;
        this.tintColorId = builder.tintColorId;
        this.aloud = builder.aloud;
        this.shownAsHead = builder.shownAsHead;
        this.shownAsAction = builder.shownAsAction;
        this.onLoaded = builder.onLoaded;
        this.textSize = builder.textSize;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }
}