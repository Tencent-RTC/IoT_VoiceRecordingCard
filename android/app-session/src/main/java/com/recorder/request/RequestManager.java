package com.recorder.request;

import com.recorder.transport.ReliableTransport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 连接级 Request 调度基础设施。
 *
 * <p>本类不理解具体业务报文，只负责 FIFO、单 in-flight、Response 公共关联、
 * 超时和连接级一刀切。排队中的 Request 不计入 Response 超时；Request 成功提交给
 * {@link ReliableTransport} 后才开始计时。
 *
 * <p>submit、Response、timeout、stall/resume 和 close 都在同一个串行执行域中处理。
 * 外部回调执行时不持有内部队列锁；Response completion 完整返回前不会发送下一条
 * Request，completion 内新提交的 Request 只会进入 FIFO。
 */
public final class RequestManager<K, R> {

    public static final int DEFAULT_MAX_QUEUED_REQUESTS = 32;
    public static final long DEFAULT_MAX_REQUEST_ID = 2147483647L;

    /** 按 Request ID 构造一条完整、已编码的业务 Request。 */
    public interface RequestEncoder {
        byte[] encode(long requestId);
    }

    /** Request 即将成为 in-flight；此回调完成后才会实际发送。 */
    public interface DispatchCallback {
        void onDispatch(long requestId);
    }

    /** matching Response 的业务处理；返回前不会发送下一条 Request。 */
    public interface Completion<R> {
        void onResponse(R response);
    }

    public interface Listener {
        void onLog(String message);

        /** 管理器已经进入终态、清空全部 Request 并发起断链。 */
        void onFatalError(String reason);
    }

    private interface Operation<T> {
        T run();
    }

    private enum State {
        ACTIVE,
        TERMINAL,
        CLOSED
    }

    private static final class ManagedRequest<K, R> {
        final long id;
        final K expectedResponseType;
        final byte[] encodedRequest;
        final DispatchCallback dispatchCallback;
        final Completion<R> completion;
        boolean dispatched;
        boolean sendCompleted;
        boolean completing;

        ManagedRequest(long id, K expectedResponseType, byte[] encodedRequest,
                       DispatchCallback dispatchCallback, Completion<R> completion) {
            this.id = id;
            this.expectedResponseType = expectedResponseType;
            this.encodedRequest = encodedRequest;
            this.dispatchCallback = dispatchCallback;
            this.completion = completion;
        }
    }

    private static final class QueuedOperation<T> {
        final Operation<T> operation;
        final CountDownLatch done = new CountDownLatch(1);
        T result;
        RuntimeException failure;

        QueuedOperation(Operation<T> operation) {
            this.operation = operation;
        }
    }

    private final ReliableTransport transport;
    private final Listener listener;
    private final long requestTimeoutMs;
    private final int maxQueuedRequests;
    private final long maxRequestId;
    private final ScheduledExecutorService timer;

    /** 只保护串行操作队列，不在持有该锁时调用任何外部代码。 */
    private final Object operationLock = new Object();
    private final ArrayDeque<QueuedOperation<?>> operations = new ArrayDeque<>();
    private final ArrayDeque<Runnable> postActions = new ArrayDeque<>();
    private volatile Thread operationThread;
    private boolean drainingOperations;

    /** 以下状态只允许在串行执行域内读写。 */
    private State state = State.ACTIVE;
    private long nextRequestId = 1L;
    private final ArrayDeque<ManagedRequest<K, R>> queuedRequests = new ArrayDeque<>();
    private ManagedRequest<K, R> inFlightRequest;
    private boolean pumping;
    /** encoder 属于构造阶段，不允许重入提交同一个 RequestManager。 */
    private boolean encodingRequest;

    private ScheduledFuture<?> timeoutTask;
    private long timeoutDeadlineNanos;
    private long timeoutRemainingMs;
    private boolean timeoutActive;
    private boolean timeoutSuspended;

    public RequestManager(ReliableTransport transport, Listener listener,
                          long requestTimeoutMs) {
        this(transport, listener, requestTimeoutMs, DEFAULT_MAX_QUEUED_REQUESTS,
                DEFAULT_MAX_REQUEST_ID);
    }

    /** 后两个参数开放给容量配置及边界测试；线上 Request ID 上限应保持协议值。 */
    public RequestManager(ReliableTransport transport, Listener listener,
                          long requestTimeoutMs, int maxQueuedRequests,
                          long maxRequestId) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.listener = Objects.requireNonNull(listener, "listener");
        if (requestTimeoutMs <= 0L) {
            throw new IllegalArgumentException("requestTimeoutMs must be positive");
        }
        if (maxQueuedRequests <= 0) {
            throw new IllegalArgumentException("maxQueuedRequests must be positive");
        }
        if (maxRequestId <= 0L) {
            throw new IllegalArgumentException("maxRequestId must be positive");
        }
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxQueuedRequests = maxQueuedRequests;
        this.maxRequestId = maxRequestId;
        this.timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "request-manager-timer");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * 投递一条 Request。若当前已有 in-flight，则进入 FIFO。
     *
     * @return Request 是否被接纳；返回 false 表示管理器已关闭，或本次投递触发了
     *         ID 耗尽/队列溢出的一刀切
     */
    public boolean submit(K expectedResponseType, RequestEncoder encoder,
                          DispatchCallback dispatchCallback, Completion<R> completion) {
        Objects.requireNonNull(expectedResponseType, "expectedResponseType");
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(dispatchCallback, "dispatchCallback");
        Objects.requireNonNull(completion, "completion");
        if (Thread.currentThread() == operationThread && encodingRequest) {
            fail("RequestEncoder 不得回调同一个 RequestManager");
            return false;
        }
        return runSerialized(() -> doSubmit(expectedResponseType, encoder,
                dispatchCallback, completion), false);
    }

    /**
     * 提交一条已解码 Response。调用方只负责从公共响应头提取类型、ID 和成功状态。
     * Event、Audio Frame、File Chunk 不得调用本方法。
     */
    public void onResponse(K actualResponseType, long requestId, boolean successful,
                           String failureReason, R response) {
        runSerialized(() -> {
            doOnResponse(actualResponseType, requestId, successful, failureReason, response);
            return null;
        }, null);
    }

    /** 本端发送方向软停滞：冻结当前 in-flight Request 的剩余超时。 */
    public void onSendStalled() {
        runSerialized(() -> {
            doOnSendStalled();
            return null;
        }, null);
    }

    /** 本端发送方向恢复：从冻结的剩余预算继续计时，不重新发送 Request。 */
    public void onSendResumed() {
        runSerialized(() -> {
            doOnSendResumed();
            return null;
        }, null);
    }

    /** 非 Response 的协议致命错误也可汇入同一连接级一刀切出口。 */
    public void fail(String reason) {
        runSerialized(() -> {
            enterFatal(reason == null ? "RequestManager 致命错误" : reason);
            return null;
        }, null);
    }

    public boolean isIdle() {
        return runSerialized(() -> state == State.ACTIVE
                && inFlightRequest == null && queuedRequests.isEmpty(), false);
    }

    public int queuedRequestCount() {
        return runSerialized(queuedRequests::size, 0);
    }

    /** 连接结束：取消并丢弃全部 Request，但不再次触发 fatal 或 stop。 */
    public void close() {
        runSerialized(() -> {
            if (state != State.CLOSED) {
                state = State.CLOSED;
                clearRequests();
            }
            return null;
        }, null);
    }

    /** 释放连接级定时器线程。 */
    public void shutdown() {
        close();
        timer.shutdownNow();
    }

    private boolean doSubmit(K expectedResponseType, RequestEncoder encoder,
                             DispatchCallback dispatchCallback, Completion<R> completion) {
        if (state != State.ACTIVE) {
            return false;
        }
        if (inFlightRequest != null && queuedRequests.size() >= maxQueuedRequests) {
            enterFatal("Request 队列溢出（等待上限 " + maxQueuedRequests + "）");
            return false;
        }
        if (nextRequestId < 1L || nextRequestId > maxRequestId) {
            enterFatal("本连接 Request id 已耗尽，必须重连后继续");
            return false;
        }

        long requestId = nextRequestId++;
        final byte[] encoded;
        encodingRequest = true;
        try {
            encoded = encoder.encode(requestId);
        } catch (RuntimeException e) {
            enterFatal("Request 编码异常: " + describe(e));
            return false;
        } finally {
            encodingRequest = false;
        }
        if (state != State.ACTIVE) {
            return false;
        }
        if (encoded == null || encoded.length == 0) {
            enterFatal("Request 编码结果为空");
            return false;
        }

        ManagedRequest<K, R> request = new ManagedRequest<>(requestId,
                expectedResponseType, encoded.clone(), dispatchCallback, completion);
        if (inFlightRequest == null) {
            inFlightRequest = request;
        } else {
            queuedRequests.addLast(request);
        }

        pumpRequests();
        return state == State.ACTIVE;
    }

    private void doOnResponse(K actualResponseType, long requestId, boolean successful,
                              String failureReason, R response) {
        if (state != State.ACTIVE) {
            return;
        }
        if (inFlightRequest == null || !inFlightRequest.dispatched) {
            enterFatal("收到无对应 in-flight Request 的 Response: " + actualResponseType);
            return;
        }
        if (inFlightRequest.completing) {
            enterFatal("当前 Request completion 尚未结束时收到 Response: "
                    + actualResponseType);
            return;
        }
        if (!Objects.equals(actualResponseType, inFlightRequest.expectedResponseType)) {
            enterFatal("Response 类型(" + actualResponseType + ")与 in-flight Request("
                    + inFlightRequest.expectedResponseType + ")不匹配");
            return;
        }
        if (requestId != inFlightRequest.id) {
            enterFatal("Response id(" + requestId + ")与 in-flight Request id("
                    + inFlightRequest.id + ")不一致");
            return;
        }
        if (!successful) {
            String detail = failureReason == null || failureReason.isEmpty()
                    ? "远端返回失败 Response" : failureReason;
            enterFatal(detail);
            return;
        }

        ManagedRequest<K, R> completedRequest = inFlightRequest;
        completedRequest.completing = true;
        cancelTimeout();
        try {
            completedRequest.completion.onResponse(response);
        } catch (RuntimeException e) {
            enterFatal("Response completion 异常: " + describe(e));
            return;
        }
        if (state != State.ACTIVE || inFlightRequest != completedRequest) {
            return;
        }

        inFlightRequest = null;
        promoteNext();
        pumpRequests();
    }

    private void doOnSendStalled() {
        if (state != State.ACTIVE || inFlightRequest == null
                || !inFlightRequest.dispatched || inFlightRequest.completing
                || timeoutSuspended) {
            return;
        }
        timeoutSuspended = true;
        if (timeoutTask != null) {
            long remainingNanos = timeoutDeadlineNanos - System.nanoTime();
            timeoutRemainingMs = Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(Math.max(0L, remainingNanos)));
            timeoutTask.cancel(false);
            timeoutTask = null;
        } else if (!timeoutActive) {
            timeoutRemainingMs = requestTimeoutMs;
        }
        safeLog("发送链路暂时停滞，已暂停 Request 超时计时");
    }

    private void doOnSendResumed() {
        if (state != State.ACTIVE || inFlightRequest == null
                || !inFlightRequest.dispatched || inFlightRequest.completing
                || !timeoutSuspended) {
            return;
        }
        timeoutSuspended = false;
        if (inFlightRequest.sendCompleted) {
            long delayMs = timeoutRemainingMs > 0L ? timeoutRemainingMs : requestTimeoutMs;
            scheduleTimeout(inFlightRequest, delayMs);
        }
        safeLog("发送链路已恢复，继续 Request 超时计时");
    }

    private void promoteNext() {
        if (inFlightRequest == null && !queuedRequests.isEmpty()) {
            inFlightRequest = queuedRequests.removeFirst();
        }
    }

    /**
     * 在串行域内 pump。同步 transport/mock 即使在 send() 栈内回调 onResponse，
     * 也只会在当前 send 返回、且 completion 完成后再发送下一条 Request。
     */
    private void pumpRequests() {
        if (pumping || state != State.ACTIVE) {
            return;
        }
        pumping = true;
        try {
            while (state == State.ACTIVE) {
                promoteNext();
                ManagedRequest<K, R> request = inFlightRequest;
                if (request == null || request.dispatched || request.completing) {
                    return;
                }

                try {
                    request.dispatchCallback.onDispatch(request.id);
                } catch (RuntimeException e) {
                    enterFatal("Request dispatch 回调异常: " + describe(e));
                    return;
                }
                if (state != State.ACTIVE || inFlightRequest != request) {
                    continue;
                }

                request.dispatched = true;
                try {
                    transport.send(request.encodedRequest);
                } catch (RuntimeException e) {
                    enterFatal("Request 发送异常: " + describe(e));
                    return;
                }
                request.sendCompleted = true;

                // send() 可能同步触发 Response，并由 completion 清除当前 Request。
                if (state != State.ACTIVE || inFlightRequest != request
                        || request.completing) {
                    continue;
                }
                armTimeoutAfterSend(request);
                return;
            }
        } finally {
            pumping = false;
        }
    }

    private void armTimeoutAfterSend(ManagedRequest<K, R> request) {
        boolean stalledDuringSend = timeoutSuspended;
        cancelTimeout();
        timeoutRemainingMs = requestTimeoutMs;
        if (stalledDuringSend || transport.isSendStalled()) {
            timeoutSuspended = true;
            safeLog("发送链路正处于软停滞，暂不启动 Request 超时计时");
            return;
        }
        scheduleTimeout(request, requestTimeoutMs);
    }

    private void scheduleTimeout(ManagedRequest<K, R> request, long delayMs) {
        timeoutActive = true;
        timeoutRemainingMs = delayMs;
        timeoutDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        timeoutTask = timer.schedule(() -> runSerialized(() -> {
            doOnTimeout(request);
            return null;
        }, null), delayMs, TimeUnit.MILLISECONDS);
    }

    private void doOnTimeout(ManagedRequest<K, R> request) {
        if (state == State.ACTIVE && timeoutActive && !timeoutSuspended
                && inFlightRequest == request && !request.completing) {
            enterFatal("Request 超时（" + requestTimeoutMs
                    + "ms，不计发送停滞）: " + request.expectedResponseType);
        }
    }

    private void enterFatal(String reason) {
        if (state != State.ACTIVE) {
            return;
        }
        state = State.TERMINAL;
        clearRequests();
        deferPostAction(() -> {
            safeLog("RequestManager 一刀切：" + reason);
            try {
                transport.stop();
            } catch (RuntimeException e) {
                safeLog("RequestManager 停止传输异常: " + describe(e));
            }
            try {
                listener.onFatalError(reason);
            } catch (RuntimeException e) {
                safeLog("RequestManager fatal 回调异常: " + describe(e));
            }
        });
    }

    private void clearRequests() {
        cancelTimeout();
        inFlightRequest = null;
        queuedRequests.clear();
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        timeoutDeadlineNanos = 0L;
        timeoutRemainingMs = 0L;
        timeoutActive = false;
        timeoutSuspended = false;
    }

    private void safeLog(String message) {
        try {
            listener.onLog(message);
        } catch (RuntimeException ignored) {
            // 日志实现不得阻断一刀切或队列推进。
        }
    }

    private void deferPostAction(Runnable action) {
        synchronized (operationLock) {
            postActions.addLast(action);
        }
    }

    /**
     * 同步进入单一执行域。若回调重入管理器，则在当前操作线程直接执行；其他线程
     * 排队并等待。内部队列锁只用于领取操作和发布结果，绝不覆盖业务回调或 transport。
     */
    private <T> T runSerialized(Operation<T> operation, T closedFallback) {
        if (Thread.currentThread() == operationThread) {
            return operation.run();
        }

        QueuedOperation<T> queued = new QueuedOperation<>(operation);
        boolean shouldDrain = false;
        synchronized (operationLock) {
            if (timer.isShutdown()) {
                return closedFallback;
            }
            operations.addLast(queued);
            if (!drainingOperations) {
                drainingOperations = true;
                operationThread = Thread.currentThread();
                shouldDrain = true;
            }
        }

        if (shouldDrain) {
            drainOperations();
        } else {
            await(queued.done);
        }
        if (queued.failure != null) {
            throw queued.failure;
        }
        return queued.result;
    }

    private void drainOperations() {
        List<Runnable> actions;
        while (true) {
            QueuedOperation<?> queued;
            synchronized (operationLock) {
                queued = operations.pollFirst();
                if (queued == null) {
                    drainingOperations = false;
                    operationThread = null;
                    actions = new ArrayList<>(postActions);
                    postActions.clear();
                    break;
                }
            }
            executeOperation(queued);
        }
        for (Runnable action : actions) {
            action.run();
        }
    }

    private static <T> void executeOperation(QueuedOperation<T> queued) {
        try {
            queued.result = queued.operation.run();
        } catch (RuntimeException e) {
            queued.failure = e;
        } finally {
            queued.done.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(RuntimeException error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
