package com.github.kaneteulin.anticheat;

import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 플레이어 한 명의 검사 상태. 패킷 수(packets)만 네트워크 스레드에서 바뀌고, 나머지는 서버(메인) 스레드에서만 쓴다.
 */
public final class PlayerData {

    // ---- 위반 점수 ----
    final Map<String, Double> violations = new HashMap<>();
    final Map<String, Long> lastAlert = new HashMap<>();

    // ---- 이동 (공중 상태) ----
    /** 이 시각(ms)까지 이동 검사 제외 (텔레포트, 넉백, 폭발 등 직후) */
    long exemptUntil;
    /** 마지막으로 땅(또는 물, 사다리 등)에 있었을 때의 높이 */
    double groundY;
    /** 땅을 떠난 뒤 가장 높았던 높이 */
    double maxY;
    /** 땅을 떠난 뒤 이동 횟수 */
    int airMoves;
    /** 떨어지지 않고 공중에 머문 연속 이동 횟수 */
    int hoverMoves;
    /** 공중에서 땅에 닿았다고 보낸 연속 이동 횟수 */
    int groundSpoofMoves;
    /** 슬라임/침대에서 튀어 오른 중 (점프 높이 검사 제외) */
    boolean bounce;
    /** 이번 공중 상태에서 점프 높이 위반을 이미 기록했는지 */
    boolean jumpFlagged;

    // ---- 채굴 ----
    Block digBlock;
    long digStart;
    float digSpeed;
    boolean digging;
    final Deque<Long> breaks = new ArrayDeque<>();

    // ---- 클릭 / 활 / 인벤토리 ----
    final Deque<Long> swings = new ArrayDeque<>();
    int highCpsSeconds;
    final Deque<Long> bowShots = new ArrayDeque<>();
    final Deque<Long> invClicks = new ArrayDeque<>();
    int highInvClickSeconds;
    /** 인벤토리(상자 포함)가 열려 있다고 판단한 시각, 0 이면 닫힘 */
    long inventoryOpenSince;
    int invMoveCount;
    int invRotateCount;
    long resurrectAt;

    // ---- 패킷 ----
    final AtomicInteger packets = new AtomicInteger();

    // ---- 오탐 위험 높음 ----
    /** 타이머: 네트워크 스레드에서 갱신 (클라이언트 틱 종료 패킷 기준) */
    final TimerState timer = new TimerState();
    double speedBuffer;
    int phaseBuffer;
    int glideAscendMoves;
    int glideHoverMoves;
    long elytraBoostUntil;
    long lastAttackAt;
    int lastAttackTarget;
    final Deque<Long> bridgePlaces = new ArrayDeque<>();

    /** 타이머 검사 상태. 네트워크 스레드와 서버 스레드가 함께 쓰므로 동기화한다 */
    static final class TimerState {
        private long last;
        private double balance;
        private volatile boolean resetRequested;
        private final AtomicInteger flags = new AtomicInteger();
        private volatile double lastBalance;

        /** 클라이언트가 한 틱을 마칠 때마다 (정상이면 50ms 간격) */
        synchronized void onClientTick(long nanos) {
            if (resetRequested || last == 0) {
                resetRequested = false;
                last = nanos;
                balance = 0;
                return;
            }
            double elapsed = (nanos - last) / 1_000_000.0;
            last = nanos;
            // 틱 하나당 50ms 를 써야 정상. 빨리 오면 balance 가 쌓인다
            balance += 50.0 - elapsed;
            // 네트워크가 멈췄다가 밀린 패킷이 한꺼번에 와도 잡히지 않도록, 늦게 온 시간은 최대 10초까지 인정
            if (balance < -10_000) {
                balance = -10_000;
            }
            if (balance > 400) {
                lastBalance = balance;
                balance = 0;
                flags.incrementAndGet();
            }
        }

        void reset() {
            resetRequested = true;
        }

        int takeFlags() {
            return flags.getAndSet(0);
        }

        double lastBalance() {
            return lastBalance;
        }
    }

    void resetAir(double y) {
        groundY = y;
        maxY = y;
        airMoves = 0;
        hoverMoves = 0;
        groundSpoofMoves = 0;
        jumpFlagged = false;
    }

    void exempt(long millis) {
        exemptUntil = Math.max(exemptUntil, System.currentTimeMillis() + millis);
        speedBuffer = 0;
        phaseBuffer = 0;
    }

    boolean isExemptNow() {
        return System.currentTimeMillis() < exemptUntil;
    }

    static int countSince(Deque<Long> times, long since) {
        while (!times.isEmpty() && times.peekFirst() < since) {
            times.pollFirst();
        }
        return times.size();
    }
}
