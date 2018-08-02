package com.chattylabs.demo.user.assistant;

import android.os.Bundle;
import android.support.v7.widget.RecyclerView;

import com.chattylabs.android.user.interaction.UserAssistantComponent;
import com.chattylabs.sdk.android.voice.VoiceInteractionComponent;

import javax.inject.Inject;

import dagger.android.support.DaggerAppCompatActivity;

public class MainActivity extends DaggerAppCompatActivity {

    @Inject VoiceInteractionComponent voiceInteractionComponent;
    private UserAssistantComponent assistantComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        RecyclerView view = findViewById(R.id.user_assistant);
        assistantComponent = new UserAssistantHelper(view, voiceInteractionComponent).create();
    }

    @Override
    protected void onStop() {
        super.onStop();
        assistantComponent.pause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        assistantComponent.resume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        assistantComponent.release();
    }
}
