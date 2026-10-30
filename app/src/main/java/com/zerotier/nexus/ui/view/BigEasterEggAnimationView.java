package com.zerotier.nexus.ui.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BigEasterEggAnimationView extends View {

    private Paint starPaint;
    private List<Star> stars = new ArrayList<>();
    private Random random = new Random();
    private boolean isRunning = false;
    private float progress = 0f;
    private ValueAnimator animator;

    private enum Mode { STAR_WARP, FIREWORKS }
    private Mode mode = Mode.STAR_WARP;
    private List<Particle> particles = new ArrayList<>();

    public BigEasterEggAnimationView(Context context) {
        super(context);
        init();
    }

    public BigEasterEggAnimationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        starPaint = new Paint();
        starPaint.setColor(Color.WHITE);
        starPaint.setAntiAlias(true);
        starPaint.setStyle(Paint.Style.FILL);
    }

    private void initStars() {
        stars.clear();
        int starCount = 150;
        for (int i = 0; i < starCount; i++) {
            stars.add(new Star());
        }
    }

    public void startEasterEggAnimation(final Runnable onEnd) {
        mode = Mode.STAR_WARP;
        initStars();
        isRunning = true;
        setAlpha(1f);
        setVisibility(View.VISIBLE);
        setBackgroundColor(Color.BLACK);

        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(3000);
        animator.setInterpolator(new AccelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isRunning = false;
                if (onEnd != null) onEnd.run();
                fadeOut();
            }
        });
        animator.start();
    }

    public void startFireworks(float startX, float startY) {
        mode = Mode.FIREWORKS;
        particles.clear();
        int particleCount = 100;
        int[] colors = {Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.WHITE};
        for (int i = 0; i < particleCount; i++) {
            particles.add(new Particle(startX, startY, colors[random.nextInt(colors.length)]));
        }
        
        isRunning = true;
        setAlpha(1f);
        setVisibility(View.VISIBLE);
        setBackgroundColor(Color.TRANSPARENT);

        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(1500);
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            for (Particle p : particles) {
                p.update();
            }
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isRunning = false;
                fadeOut();
            }
        });
        animator.start();
    }

    private void fadeOut() {
        animate().alpha(0f).setDuration(500).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(View.GONE);
                setAlpha(1f);
                setBackgroundColor(Color.TRANSPARENT);
            }
        }).start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isRunning) return;

        if (mode == Mode.STAR_WARP) {
            drawStarWarp(canvas);
        } else {
            drawFireworks(canvas);
        }
    }

    private void drawStarWarp(Canvas canvas) {
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        for (Star star : stars) {
            float x = centerX + star.x * progress * 2;
            float y = centerY + star.y * progress * 2;
            float size = star.size * (1 + progress * 3);
            starPaint.setColor(Color.WHITE);
            starPaint.setAlpha((int) (255 * (1 - progress * 0.5f)));
            
            float tailX = centerX + star.x * (progress * 0.9f) * 2;
            float tailY = centerY + star.y * (progress * 0.9f) * 2;
            
            starPaint.setStrokeWidth(size);
            canvas.drawLine(tailX, tailY, x, y, starPaint);
            canvas.drawCircle(x, y, size / 2, starPaint);
        }
    }

    private void drawFireworks(Canvas canvas) {
        for (Particle p : particles) {
            starPaint.setColor(p.color);
            starPaint.setAlpha((int) (255 * (1 - progress)));
            canvas.drawCircle(p.x, p.y, p.size, starPaint);
        }
    }

    private class Star {
        float x, y;
        float size;

        Star() {
            double angle = random.nextDouble() * 2 * Math.PI;
            float dist = random.nextFloat() * 500 + 50;
            x = (float) (Math.cos(angle) * dist);
            y = (float) (Math.sin(angle) * dist);
            size = random.nextFloat() * 4 + 2;
        }
    }

    private class Particle {
        float x, y;
        float vx, vy;
        float size;
        int color;

        Particle(float startX, float startY, int color) {
            this.x = startX;
            this.y = startY;
            this.color = color;
            double angle = random.nextDouble() * 2 * Math.PI;
            float speed = random.nextFloat() * 20 + 5;
            this.vx = (float) (Math.cos(angle) * speed);
            this.vy = (float) (Math.sin(angle) * speed);
            this.size = random.nextFloat() * 8 + 4;
        }

        void update() {
            x += vx;
            y += vy;
            vy += 0.5f; // Gravity
            vx *= 0.98f; // Air resistance
            vy *= 0.98f;
        }
    }
}
