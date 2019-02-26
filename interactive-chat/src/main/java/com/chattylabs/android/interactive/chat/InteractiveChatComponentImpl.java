package com.chattylabs.android.interactive.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.support.annotation.VisibleForTesting;
import android.support.text.emoji.EmojiCompat;
import android.support.text.emoji.bundled.BundledEmojiCompatConfig;
import android.support.v4.util.Pools;
import android.support.v4.util.SimpleArrayMap;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.TypedValue;

import com.chattylabs.android.commons.DimensionUtils;
import com.chattylabs.sdk.android.voice.ConversationalFlowComponent;
import com.chattylabs.sdk.android.voice.OnComponentSetup;
import com.chattylabs.sdk.android.voice.RecognizerListener;
import com.chattylabs.sdk.android.voice.SpeechRecognizerComponent;
import com.chattylabs.sdk.android.voice.SpeechSynthesizerComponent;
import com.chattylabs.sdk.android.voice.SynthesizerListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;


final class InteractiveChatComponentImpl extends InteractiveChatFlow.Edge implements InteractiveChatComponent {

    private static final long DEFAULT_MESSAGE_DELAY = 500L;

    @VisibleForTesting
    static final String LAST_VISITED_NODE = BuildConfig.APPLICATION_ID + ".LAST_VISITED_NODE";
    @VisibleForTesting
    static final String VISITED_NODES = BuildConfig.APPLICATION_ID + ".VISITED_NODES";

    public static final int LOADING_DISPLAY_DELAY = 1000;
    public static final int ITEM_SEPARATOR_SIZE_DIP = 4;

    private final Pools.Pool<ArrayList<InteractiveChatNode>> mListPool = new Pools.SimplePool<>(10);
    private final SimpleArrayMap<InteractiveChatNode, ArrayList<InteractiveChatNode>> graph = new SimpleArrayMap<>();
    private final LinearLayoutManager layoutManager;
    private final SharedPreferences sharedPreferences;
    private ConversationalFlowComponent speechComponent;
    private SpeechSynthesizerComponent speechSynthesizer;
    private SpeechRecognizerComponent speechRecognizer;

    private Timer timer;
    private TimerTask task;
    private Handler uiThreadHandler = new Handler(Looper.getMainLooper());
    private Handler loadingHandler = new Handler(Looper.getMainLooper());
    private Handler speechHandler = new Handler(Looper.getMainLooper());
    private Handler scheduleHandler = new Handler(Looper.getMainLooper());
    private InteractiveChatNode currentNode;
    private Action lastAction;
    private Runnable doneListener;
    private boolean paused;
    private boolean enableSynthesizer;
    private boolean enableRecognizer;
    private boolean synthesizerReady;
    private boolean recognizerReady;
    private boolean speechInProgress;
    private boolean enableLastState;

    private final InteractiveChatAdapter adapter = new InteractiveChatAdapter(
            (view, action) -> perform(action));

    @SuppressLint("MissingPermission")
    InteractiveChatComponentImpl(Builder builder) {
        RecyclerView recyclerView = builder.recyclerView;
        if (recyclerView.getItemDecorationCount() == 0) {
            int s = DimensionUtils.getDimension(recyclerView.getContext(),
                    TypedValue.COMPLEX_UNIT_DIP, ITEM_SEPARATOR_SIZE_DIP);
            SeparatorItemDecoration separatorItemDecoration = new SeparatorItemDecoration(s);
            uiThreadHandler.post(() -> {
                recyclerView.addItemDecoration(separatorItemDecoration);
            });
        }
        timer = new Timer();
        enableLastState = builder.withLastStateEnabled;
        speechComponent = builder.voiceComponent;
        doneListener = builder.doneListener;
        layoutManager = ((LinearLayoutManager) recyclerView.getLayoutManager());
        //layoutManager.setSmoothScrollbarEnabled(false);
        sharedPreferences = recyclerView.getContext().getSharedPreferences(
                "interactive_chat", Context.MODE_PRIVATE);
        uiThreadHandler.post(() -> {
            //recyclerView.setItemAnimator(null);
            recyclerView.setAdapter(adapter);
            EmojiCompat.Config config = new BundledEmojiCompatConfig(recyclerView.getContext());
            EmojiCompat.init(config);
        });
    }

    @Override
    void start(@NonNull InteractiveChatNode root) {
        String lastSavedNodeId = getLastVisitedNodeId((HasId) root);
        InteractiveChatNode lastSavedNode = getNode(lastSavedNodeId);
        adapter.addItem(root);
        traverse(adapter.getItems(), root, lastSavedNode);
        adapter.checkViewHolders();
        currentNode = lastSavedNode;
        next();
    }

    @Override
    public void enableSpeechSynthesizer(Context context, boolean enable) {
        this.enableSynthesizer = enable;
        if (speechComponent != null && synthesizerReady)
            speechSynthesizer = speechComponent.getSpeechSynthesizer(context);
    }

    @Override
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public void enableSpeechRecognizer(Context context, boolean enable) {
        this.enableRecognizer = enable;
        if (speechComponent != null && recognizerReady)
            speechRecognizer = speechComponent.getSpeechRecognizer(context);
    }

    @Override
    public void addNode(@NonNull InteractiveChatNode node) {
        if (!graph.containsKey(node)) {
            graph.put(node, null);
        }
    }

    @Override
    public boolean isSpeechSynthesizerEnabled() {
        return enableSynthesizer;
    }

    @Override
    public boolean isSpeechRecognizerEnabled() {
        return enableRecognizer;
    }

    @Override
    public InteractiveChatFlow prepare() {
        return new InteractiveChatFlow(this);
    }

    @Override
    public void next() {
        boolean canTrack = !(lastAction instanceof CanSkipTracking) ||
                !((CanSkipTracking) lastAction).skipTracking();
        if (lastAction != null && canTrack) trackLastNode();
        show(getNext(), DEFAULT_MESSAGE_DELAY);
    }

    @Override
    public void setupSpeech(Context context, OnComponentSetup onSetup) {
        speechInProgress = true;
        showLoading();
        speechComponent.setup(context, status -> {
            synthesizerReady = status.getSynthesizerStatus() ==
                    SynthesizerListener.Status.AVAILABLE;
            recognizerReady = status.getRecognizerStatus() ==
                    RecognizerListener.Status.AVAILABLE;

            // FIXME: Probably a Dagger issue
            // Second time it exists the App seems like the TTS is still alive, so triedTtsData
            // value is TRUE, causing checkAvailability never to be called.
            // TODO: P2 - to show TTS error screen
            speechHandler.post(() -> {
                hideLoading();
                onSetup.execute(status);
            });
        });
    }

    @Override
    public void release() {
        cancel();
        loadingHandler.removeCallbacksAndMessages(null);
        uiThreadHandler.removeCallbacksAndMessages(null);
        speechHandler.removeCallbacksAndMessages(null);
        scheduleHandler.removeCallbacksAndMessages(null);
        if (speechComponent != null) {
            speechComponent.shutdown();
        }
        graph.clear();
        currentNode = null;
        lastAction = null;
        doneListener = null;
        paused = false;
        enableSynthesizer = false;
        enableRecognizer = false;
        synthesizerReady = false;
        recognizerReady = false;
        speechInProgress = false;
        enableLastState = false;
    }

    private void cancel() {
        if (timer != null) {
            if (task != null) {
                task.cancel();
            }
            timer.cancel();
            timer = new Timer();
        }
        if (speechComponent != null) {
            speechComponent.stop();
        }
    }

    @Override
    public void pause() {
        paused = true;
        cancel();
    }

    @Override
    public void resume() {
        if (paused) {
            paused = false;
            if (task != null) {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (task != null) {
                            task.run();
                        }
                    }
                }, task.scheduledExecutionTime());
            }
        }
    }

    @Override
    public void showLoading() {
        loadingHandler.postDelayed(() -> {
            int size = adapter.getItemCount();
            if (size == 0 || !Loading.class.isInstance(
                    adapter.getItem(size - 1))) {
                adapter.addItem(new Loading());
                layoutManager.scrollToPosition(size);
            }
        }, LOADING_DISPLAY_DELAY);
    }

    @Override
    public void hideLoading() {
        if (loadingHandler != null) loadingHandler.removeCallbacksAndMessages(null);
        int position = adapter.getLastPositionOf(Loading.class);
        if (position != -1 && uiThreadHandler != null)
            uiThreadHandler.post(() -> adapter.removeItem(position));
    }

    private void trackLastNode() {
        ArrayList<InteractiveChatNode> outgoingEdges = getOutgoingEdges(lastAction);
        if (outgoingEdges != null && !outgoingEdges.isEmpty()) {
            if (outgoingEdges.size() == 1) {
                InteractiveChatNode node = outgoingEdges.get(0);
                if (!Action.class.isInstance(node)) {
                    if (enableLastState) setLastVisitedNode((HasId) node);
                } else {
                    throw new IllegalStateException("An Action can only be " +
                            "connected to a Message");
                }
            } else {
                throw new IllegalStateException("An Action cannot have multiple " +
                        "connections in the graph");
            }
        }
    }

    private void perform(Action action) {
        currentNode = action;
        lastAction = action;
        if (speechComponent != null) speechComponent.stop();
        if (action instanceof HasOnSelected && ((HasOnSelected) action).onSelected() != null) {
            ((HasOnSelected) action).onSelected().execute(action);
        }
        boolean continueFlow = !(action instanceof CanStopFlow) || !((CanStopFlow) action).stopFlow();
        if (continueFlow) {
            boolean canTrack = !(action instanceof CanSkipTracking) || !((CanSkipTracking) action).skipTracking();
            if (canTrack && enableLastState) saveVisitedNode((HasId) action);
            selectLastVisitedAction();
            next();
        }
    }

    @Override
    public void selectLastVisitedAction() {
        InteractiveChatNode node = getNode();
        Action action = null;
        removeLastItem();
        if (Action.class.isInstance(node)) {
            action = (Action) node;
        } else if (ActionList.class.isInstance(node)) {
            action = ((ActionList) node).getVisited(getVisitedNodes());
        }
        if (action != null) placeSelectedAction(action);
    }

    private void placeSelectedAction(Action action) {
        lastAction = action;
        if (action instanceof CanHandleState) {
            ((CanHandleState) action).restoreSavedState(sharedPreferences);
        }
        addLast(((MustBuildActionFeedback) action).buildActionFeedback());
    }

    private void addLast(InteractiveChatNode node) {
        uiThreadHandler.post(() -> {
            adapter.addItem(node);
            layoutManager.scrollToPosition(adapter.getItemCount() - 1);
        });
    }

    private void removeLastItem() {
        if (adapter.getItemCount() > 0) uiThreadHandler.post(() ->
                adapter.removeItem(adapter.getItemCount() - 1));
    }

    private void setLastVisitedNode(HasId node) {
        sharedPreferences.edit().putString(LAST_VISITED_NODE,
                node.getId()).apply();
    }

    private void saveVisitedNode(HasId node) {
        Set<String> set = getVisitedNodes();
        set.add(node.getId());
        sharedPreferences.edit().putStringSet(VISITED_NODES, set).apply();
        if (node instanceof CanHandleState) {
            ((CanHandleState) node).saveState(sharedPreferences);
        }
    }

    @Override
    public Set<String> getVisitedNodes() {
        return sharedPreferences.getStringSet(VISITED_NODES, new HashSet<>());
    }

    private String getLastVisitedNodeId(HasId node) {
        return sharedPreferences.getString(LAST_VISITED_NODE, node.getId());
    }

    private InteractiveChatNode getNode() {
        return currentNode;
    }

    @Nullable
    private InteractiveChatNode getNext() {
        ArrayList<InteractiveChatNode> outgoingEdges = getOutgoingEdges(currentNode);
        if (outgoingEdges == null || outgoingEdges.isEmpty()) {
            return null;
        }

        if (outgoingEdges.size() == 1) {
            InteractiveChatNode node = outgoingEdges.get(0);
            if (Action.class.isInstance(node)) {
                ArrayList<InteractiveChatNode> nodes = new ArrayList<>(1);
                nodes.add(node);
                return getActionList(nodes);
            }
            return outgoingEdges.get(0);
        } else {
            return getActionList(outgoingEdges);
        }
    }

    @Override
    public void removeLastState() {
        sharedPreferences.edit().remove(LAST_VISITED_NODE).apply();
        sharedPreferences.edit().remove(VISITED_NODES).apply();
    }

    private void traverse(List<InteractiveChatNode> items, InteractiveChatNode root, InteractiveChatNode target) {
        if (root.equals(target)) return;
        final ArrayList<InteractiveChatNode> edges = getOutgoingEdges(root);
        if (edges != null && !edges.isEmpty()) {
            if (edges.size() == 1) {
                InteractiveChatNode node = edges.get(0);
                if (node.equals(target)) {
                    items.add(node);
                    return;
                }
                if (node instanceof Action) {
                    Action action = (Action) node;
                    if (action instanceof CanHandleState) {
                        ((CanHandleState) action).restoreSavedState(sharedPreferences);
                    }
                    if (action instanceof MustBuildActionFeedback) {
                        items.add(((MustBuildActionFeedback) action).buildActionFeedback());
                    }
                    traverse(items, action, target);
                } else {
                    items.add(node);
                    traverse(items, node, target);
                }
            } else {
                ActionList actionList = getActionList(edges);
                Action action = actionList.getVisited(getVisitedNodes());
                if (action != null) {
                    if (action instanceof CanHandleState) {
                        ((CanHandleState) action).restoreSavedState(sharedPreferences);
                    }
                    if (action instanceof MustBuildActionFeedback) {
                        items.add(((MustBuildActionFeedback) action).buildActionFeedback());
                    }
                    traverse(items, action, target);
                }
            }
        }
    }

    private ActionList getActionList(ArrayList<InteractiveChatNode> edges) {
        try {
            ActionList actionList = new ActionList();
            for (int i = 0, size = edges.size(); i < size; i++) {
                actionList.add((Action) edges.get(i));
            }
            Collections.sort(actionList);
            return actionList;
        } catch (ClassCastException ignored) {
            throw new IllegalStateException("Only actions can represent several edges in the graph");
        }
    }

    private void show(@Nullable InteractiveChatNode node, long delay) {
        if (node != null) {
            schedule(node, delay);
        } else {
            if (!ActionList.class.isInstance(currentNode)) {
                hideLoading(); // Otherwise there is no more nodes
                if (doneListener != null) doneListener.run();
            }
        }
    }

    private void schedule(InteractiveChatNode item, long timeout) {
        task = new TimerTask() {
            @Override
            public void run() {
                task = null;
                scheduleHandler.post(() -> {
                    hideLoading();
                    addLast(item);
                    if (!Action.class.isInstance(item) &&
                        !ActionList.class.isInstance(item)) {
                        currentNode = item;
                        handleNotActionNode(item);
                    } else {
                        handleActionNode(item);
                        // Never show next node automatically for actions
                        // Let the developer to choose when to do next()
                    }
                });
            }
        };
        timer.schedule(task, timeout);
    }

    private void handleActionNode(InteractiveChatNode item) {
        if (enableRecognizer && recognizerReady) {
            // show mic icon?

            ActionList actionList = (ActionList) getNext();
            if (actionList != null && CanRecognizeSpeech.class.isInstance(actionList)) {
                ((CanRecognizeSpeech) actionList).consumeRecognizer(speechRecognizer, this::perform);
            } else {
                currentNode = item;
            }

        } else if (enableRecognizer && !speechInProgress) {
            throw new IllegalStateException("Have you called #setupSpeech()?");
        } else {
            currentNode = item;
        }
    }

    private void handleNotActionNode(InteractiveChatNode item) {
        if (((HasOnLoaded) item).onLoaded() != null) {
            ((HasOnLoaded) item).onLoaded().run();
        }
        if (enableSynthesizer && synthesizerReady) {
            if (HasText.class.isInstance(item)) {
                if (CanSynthesizeSpeech.class.isInstance(item)) {
                    showLoading();
                    ((CanSynthesizeSpeech) item).consumeSynthesizer(speechSynthesizer,
                            () -> speechHandler.post(this::next));
                } else {
                    next();
                }
            } else {
                next();
            }
        } else if (enableSynthesizer && !speechInProgress) {
            throw new IllegalStateException("Have you called #setupSpeech()?");
        } else next();
    }

    @NonNull
    private ArrayList<InteractiveChatNode> getEmptyList() {
        ArrayList<InteractiveChatNode> list = mListPool.acquire();
        if (list == null) {
            list = new ArrayList<>();
        }
        return list;
    }

    @Nullable
    private ArrayList<InteractiveChatNode> getIncomingEdges(@NonNull InteractiveChatNode node) {
        return graph.get(node);
    }

    @Nullable
    private ArrayList<InteractiveChatNode> getOutgoingEdges(@NonNull InteractiveChatNode node) {
        ArrayList<InteractiveChatNode> result = null;
        for (int i = 0, size = graph.size(); i < size; i++) {
            ArrayList<InteractiveChatNode> edges = graph.valueAt(i);
            if (edges != null && edges.contains(node)) {
                if (result == null) {
                    result = new ArrayList<>();
                }
                result.add(graph.keyAt(i));
            }
        }
        return result;
    }

    @Override
    void addEdge(@NonNull InteractiveChatNode node, @NonNull InteractiveChatNode incomingEdge) {
        if (!graph.containsKey(node) || !graph.containsKey(incomingEdge)) {
            throw new IllegalArgumentException("All nodes must be present in the graph " +
                    "before being added as an edge");
        }

        ArrayList<InteractiveChatNode> edges = graph.get(node);
        if (edges == null) {
            // If edges is null, we should try and get one from the pool and add it to the graph
            edges = getEmptyList();
            graph.put(node, edges);
        }
        // Finally add the edge to the list
        edges.add(incomingEdge);
    }

    @Override
    public InteractiveChatNode getNode(@NonNull String id) {
        for (int i = 0, size = graph.size(); i < size; i++) {
            InteractiveChatNode node = graph.keyAt(i);
            if (node instanceof HasId && ((HasId) node).getId().equals(id)) {
                return node;
            }
        }
        throw new IllegalArgumentException("Node \"" + id + "\" does not exists in the graph. " +
                "Have you forgotten to add it with addNode(Node)?");
    }

    static Spanned makeText(CharSequence text) {
        Spanned span;
        if (text instanceof SpannableString) {
            span = ((SpannableString) text);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                span = Html.fromHtml(text.toString(), Html.FROM_HTML_MODE_COMPACT);
            } else {
                span = Html.fromHtml(text.toString());
            }
        }
        return span;
    }
}
