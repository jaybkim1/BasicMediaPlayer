package com.example.sonic.mediaplayer;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.IOException;

/*
* Created by JayB Kim
* */

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback{

    private static final String TAG = "MainActivity";

    private Player player = null;
    private Button mPlayButton;
    private Button mPauseButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surface);
        surfaceView.getHolder().addCallback(this);

        mPlayButton = (Button)findViewById(R.id.btn_play);
        mPlayButton.setOnClickListener(mOnClickListener);

        mPauseButton = (Button)findViewById(R.id.btn_pause);
        mPauseButton.setOnClickListener(mOnClickListener);
    }

    /*
    * onClick listener
    * */
    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            switch (v.getId()){

                case R.id.btn_play: // Pause To Play

                    if (player != null) {
                        player.pauseToPlay();
                    }

                    Toast.makeText(MainActivity.this, "Pause To Play!", Toast.LENGTH_SHORT).show();

                    Log.d(TAG,"PLAY");

                    break;

                case R.id.btn_pause: // Pause

                    if (player != null) {
                        player.pause();
                    }

                    Toast.makeText(MainActivity.this, "Paused!", Toast.LENGTH_SHORT).show();

                    Log.d(TAG,"PAUSE");

                    break;
            }
        }
    };

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (player == null) {
            try {
                player = new Player(holder.getSurface());
                player.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (player != null) {
            player.stop();
        }
    }
}
