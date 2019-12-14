package chattylabs.assistant;

import android.text.Spanned;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;


class FeedbackTextViewHolderBuilder implements ViewHolderBuilder {

    public static ViewHolderBuilder build() {
        return new FeedbackTextViewHolderBuilder();
    }

    @Override
    public RecyclerView.ViewHolder createViewHolder(ViewGroup viewGroup, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        View view = inflater.inflate(R.layout.item_interactive_assistant_action_text_selected,
                viewGroup, false);
        return new FeedbackTextViewHolderBuilder.ChatActionTextSelectedViewHolder(view);
    }

    static class ChatActionTextSelectedViewHolder extends RecyclerView.ViewHolder implements Binder {

        TextView textView;
        float defaultTextSize;

        ChatActionTextSelectedViewHolder(View v) {
            super(v);
            textView = (TextView) ((ViewGroup) v).getChildAt(0);
        }

        @Override
        public void onBind(ViewAdapter adapter, int position) {
            FeedbackText textSelected = (FeedbackText) adapter.getItems().get(position);
            if (defaultTextSize == 0) defaultTextSize = textView.getTextSize();
            if (textSelected.textSize > 0) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSelected.textSize);
            } else {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultTextSize);
            }
            CharSequence text = InteractiveAssistant.processEmoji(textSelected.text);
            Spanned span = InteractiveAssistant.formatHTML(text);
            textView.setText(span);
        }
    }
}