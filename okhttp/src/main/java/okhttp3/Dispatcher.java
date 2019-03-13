/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;

/**
 * 调度者定义了何时执行异步请求的策略
 * 每个调度者使用一个ExecutorService对象在内部执行Call方法。
 * 如果你提供了自己的执行者，那么它应该能够同时运行与getMaxRequests()返回值相当的Call方法。
 *
 * Policy on when async requests are executed.
 *
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 */
public final class Dispatcher {
  // 最大并行请求数
  private int maxRequests = 64;
  // 每个主机最大并行请求数
  private int maxRequestsPerHost = 5;
  // 闲置状态回调
  private @Nullable Runnable idleCallback;

  // 执行者，执行Call方法，延迟创建
  /** Executes calls. Created lazily. */
  private @Nullable ExecutorService executorService;

  // 即将执行的异步Call队列
  /** Ready async calls in the order they'll be run. */
  private final Deque<AsyncCall> readyAsyncCalls = new ArrayDeque<>();

  // 正在执行的异步Call队列，包括已经取消但还没有结束的Call
  /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
  private final Deque<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

  // 正在执行的同步Call队列，包括已经取消但还没有结束的Call
  /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
  private final Deque<RealCall> runningSyncCalls = new ArrayDeque<>();

  // 带参构造方法，传入一个执行者
  public Dispatcher(ExecutorService executorService) {
    this.executorService = executorService;
  }

  // 无参构造方法
  public Dispatcher() {
  }

  // 通过方法获取执行者对象，避免空指针和线程冲突。实际上，执行者就是一个ThreadPoolExecutor对象
  public synchronized ExecutorService executorService() {
    if (executorService == null) {
      executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
    }
    return executorService;
  }

  /**
   * 设置同步执行的最大请求数。请求队列存储在内存中，等待正在执行的Call完成
   * 当这个方法调用的时候，如果正在运行的请求超过了新的最大请求数，则这些请求仍将继续运行
   *
   * Set the maximum number of requests to execute concurrently. Above this requests queue in
   * memory, waiting for the running calls to complete.
   *
   * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
   * will remain in flight.
   */
  public synchronized void setMaxRequests(int maxRequests) {
    if (maxRequests < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequests);
    }
    this.maxRequests = maxRequests;
    promoteCalls();
  }

  public synchronized int getMaxRequests() {
    return maxRequests;
  }

  /**
   * 设置每个主机可以同步执行的最大请求数。它通过url的主机名限制请求。
   * 需要注意的是，针对单个ip地址的并行请求可能仍然超过此限制：多个主机名可能共用一个ip地址或者通过同一个代理被路由
   * 当这个方法调用的时候，如果正在运行的请求超过了新的最大请求数，则这些请求仍将继续运行
   * 主机的web socket连接不受这个限制
   *
   * Set the maximum number of requests for each host to execute concurrently. This limits requests
   * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
   * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
   * proxy.
   *
   * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
   * requests will remain in flight.
   *
   * <p>WebSocket connections to hosts <b>do not</b> count against this limit.
   */
  public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
    if (maxRequestsPerHost < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
    }
    this.maxRequestsPerHost = maxRequestsPerHost;
    promoteCalls();
  }

  public synchronized int getMaxRequestsPerHost() {
    return maxRequestsPerHost;
  }

  /**
   * 每次调度者空闲时（正在运行的Call数为0），设置一个需要被回调的方法
   * 注意：同步和异步的Call判断是否空闲的时机是不一样的。异步Call在onResponse/onFailure回调被返回后才被置为空闲态，
   * 同步Call在execute方法返回时被置为空闲态。这意味着如果你执行同步Call，直到每个返回的Response被关闭时，网络层才真正空闲
   *
   * Set a callback to be invoked each time the dispatcher becomes idle (when the number of running
   * calls returns to zero).
   *
   * <p>Note: The time at which a {@linkplain Call call} is considered idle is different depending
   * on whether it was run {@linkplain Call#enqueue(Callback) asynchronously} or
   * {@linkplain Call#execute() synchronously}. Asynchronous calls become idle after the
   * {@link Callback#onResponse onResponse} or {@link Callback#onFailure onFailure} callback has
   * returned. Synchronous calls become idle once {@link Call#execute() execute()} returns. This
   * means that if you are doing synchronous calls the network layer will not truly be idle until
   * every returned {@link Response} has been closed.
   */
  public synchronized void setIdleCallback(@Nullable Runnable idleCallback) {
    this.idleCallback = idleCallback;
  }

  /**
   * 异步Call入队等待
   * 如果当前正在执行的请求数和当前主机正在执行的请求数小于最大值，则将Call加入执行队列，使用调用者执行
   * 否则，加入等待队列
   */
  synchronized void enqueue(AsyncCall call) {
    if (runningAsyncCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
      runningAsyncCalls.add(call);
      executorService().execute(call);
    } else {
      readyAsyncCalls.add(call);
    }
  }

  /**
   * 取消目前的所有请求，包括已入队的和正在执行的。也包括已经执行的同步和异步请求。
   *
   * Cancel all calls currently enqueued or executing. Includes calls executed both {@linkplain
   * Call#execute() synchronously} and {@linkplain Call#enqueue asynchronously}.
   */
  public synchronized void cancelAll() {
    for (AsyncCall call : readyAsyncCalls) {
      call.get().cancel();
    }

    for (AsyncCall call : runningAsyncCalls) {
      call.get().cancel();
    }

    for (RealCall call : runningSyncCalls) {
      call.cancel();
    }
  }

  /**
   * 督促调用：移除等待调用队列里的Call，将其加入执行队列
   *  如果执行队列满，退出
   *  如果等待队列空，退出
   *  否则，遍历等待队列，按序移除等待队列的Call，在当前主机请求数未满的情况下加入执行队列
   *  遍历结束或执行队列满时退出
   */
  private void promoteCalls() {
    if (runningAsyncCalls.size() >= maxRequests) return; // Already running max capacity.
    if (readyAsyncCalls.isEmpty()) return; // No ready calls to promote.

    for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
      AsyncCall call = i.next();

      if (runningCallsForHost(call) < maxRequestsPerHost) {
        i.remove();
        runningAsyncCalls.add(call);
        executorService().execute(call);
      }

      if (runningAsyncCalls.size() >= maxRequests) return; // Reached max capacity.
    }
  }

  /**
   * 返回和传入的Call同一个主机的正在执行的Call数量
   */
  /** Returns the number of running calls that share a host with {@code call}. */
  private int runningCallsForHost(AsyncCall call) {
    int result = 0;
    for (AsyncCall c : runningAsyncCalls) {
      if (c.get().forWebSocket) continue;
      if (c.host().equals(call.host())) result++;
    }
    return result;
  }

  /**
   * 通过Call的execute调用，将Call加入执行队列，标志Call进入执行态
   */
  /** Used by {@code Call#execute} to signal it is in-flight. */
  synchronized void executed(RealCall call) {
    runningSyncCalls.add(call);
  }

  /**
   * 通过AsyncCall的run调用，标志Call进入完成态
   */
  /** Used by {@code AsyncCall#run} to signal completion. */
  void finished(AsyncCall call) {
    finished(runningAsyncCalls, call, true);
  }

  /**
   * 通过RealCall的execute调用，标志Call进入完成态
   */
  /** Used by {@code Call#execute} to signal completion. */
  void finished(RealCall call) {
    finished(runningSyncCalls, call, false);
  }

  /**
   * 转至完成态的具体操作：
   *  Call不在执行队列，崩溃
   *  如果是异步Call，督促调用
   *  在执行队列中加入正在等待队列的Call，执行队列为空时，执行空闲回调
   *
   */
  private <T> void finished(Deque<T> calls, T call, boolean promoteCalls) {
    int runningCallsCount;
    Runnable idleCallback;
    synchronized (this) {
      if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
      if (promoteCalls) promoteCalls();
      runningCallsCount = runningCallsCount();
      idleCallback = this.idleCallback;
    }

    if (runningCallsCount == 0 && idleCallback != null) {
      idleCallback.run();
    }
  }

  /**
   * 返回当前等待执行的Call的快照
   */
  /** Returns a snapshot of the calls currently awaiting execution. */
  public synchronized List<Call> queuedCalls() {
    List<Call> result = new ArrayList<>();
    for (AsyncCall asyncCall : readyAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * 返回当前正在执行的Call的快照
   */
  /** Returns a snapshot of the calls currently being executed. */
  public synchronized List<Call> runningCalls() {
    List<Call> result = new ArrayList<>();
    result.addAll(runningSyncCalls);
    for (AsyncCall asyncCall : runningAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  // 获取等待执行的Call总数
  public synchronized int queuedCallsCount() {
    return readyAsyncCalls.size();
  }

  // 获取正在执行的Call总数
  public synchronized int runningCallsCount() {
    return runningAsyncCalls.size() + runningSyncCalls.size();
  }
}
