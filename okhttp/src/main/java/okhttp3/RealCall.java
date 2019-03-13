/*
 * Copyright (C) 2014 Square, Inc.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.INFO;

// 真正的Call，实现了Call接口的相关方法
final class RealCall implements Call {
  final OkHttpClient client;
  final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;

  /**
   * 在Call和EventListener之间有一个周期。
   * 因此，我们需要在创建Call实例之后创建EventListener实例
   * There is a cycle between the {@link Call} and {@link EventListener} that makes this awkward.
   * This will be set after we create the call instance then create the event listener instance.
   */
  private EventListener eventListener;

  /**
   * 不掺杂重定向和身份头的原始请求
   */
  /** The application's original request unadulterated by redirects or auth headers. */
  final Request originalRequest;
  final boolean forWebSocket;

  // 需要重点关注的核心成员属性
  // Guarded by this.
  private boolean executed;

  // 私有化构造方法，由newRealCall调用
  private RealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    this.client = client;
    this.originalRequest = originalRequest;
    this.forWebSocket = forWebSocket;
    this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
  }

  // 静态方法，创建一个新的RealCall实例
  static RealCall newRealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
    // 安全地将Call实例添加到EventListener
    // 首先创建了RealCall实例，然后通过实例中的事件监听工厂(支持用户配置)，基于本身创建一个事件监听器，赋值给本身

    // Safely publish the Call instance to the EventListener.
    RealCall call = new RealCall(client, originalRequest, forWebSocket);
    call.eventListener = client.eventListenerFactory().create(call);
    return call;
  }

  // 返回原始请求
  @Override public Request request() {
    return originalRequest;
  }

  // 通过调度者执行请求，返回响应结果或调用事件监听器的callFailed方法
  @Override public Response execute() throws IOException {
    // 如果执行过，抛出状态异常
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    // 创建一个带有详细信息的Throwable，配置到 重试或追踪拦截器 里面
    captureCallStackTrace();
    // 调用事件监听器的callStart方法
    eventListener.callStart(this);
    try {
      // 调用调度者执行当前Call
      client.dispatcher().executed(this);
      // 从拦截器获取响应结果
      Response result = getResponseWithInterceptorChain();
      // 如果结果为空，抛出IO异常，提示被取消；否则，返回响应结果
      if (result == null) throw new IOException("Canceled");
      return result;
    } catch (IOException e) {
      // 异常时，调用事件监听器的callFailed方法
      eventListener.callFailed(this, e);
      throw e;
    } finally {
      // 调用调度者的finished方法，当前Call转至完全态
      client.dispatcher().finished(this);
    }
  }

  // 创建一个带有详细信息的Throwable，配置到 重试或追踪拦截器 里面
  private void captureCallStackTrace() {
    Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
    retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
  }

  // 通过调度者入队请求
  @Override public void enqueue(Callback responseCallback) {
    // 如果入队过，抛出状态异常
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    // 创建一个带有详细信息的Throwable，配置到 重试或追踪拦截器 里面
    captureCallStackTrace();
    // 调用事件监听器的callStart方法
    eventListener.callStart(this);
    // 调用调度者入队当前Call
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
  }

  // 通过调用 重试或追踪拦截器 的取消方法，终止流程，取消当前Call
  @Override public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  // 是否执行过
  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  // 通过调用 重试或追踪拦截器 的取消判断方法，判断当前Call是否取消
  @Override public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  // RealCall实例的克隆方法，不需要调用父类的克隆
  // 它根据当前的成员属性，重新创建了一个新的RealCall对象
  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public RealCall clone() {
    return RealCall.newRealCall(client, originalRequest, forWebSocket);
  }

  // 返回 重试或追踪拦截器 的流分配器
  // 这个分配器是连接和流的桥梁，在处理一个请求时，负责为该请求寻找可复用的或创建合适的连接
  // 这里涉及到三个实体：
  //  连接：建立在Socket上的物理通信通道
  //  流：  逻辑HTTP请求响应对（除HTTP/1外，其他情况一个连接均可同时拥有多个流）
  //  调用：请求过程的封装，一个Call可能涉及多个流（重定向、主机认证等情况）
  StreamAllocation streamAllocation() {
    return retryAndFollowUpInterceptor.streamAllocation();
  }

  // 异步Call，实际上就是一个带名字标识的Runnable
  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;

    // 初始化方法，传入名字 和 结果响应回调
    AsyncCall(Callback responseCallback) {
      super("OkHttp %s", redactedUrl());
      this.responseCallback = responseCallback;
    }

    // 通过原始请求获取主机名
    String host() {
      return originalRequest.url().host();
    }

    // 获取原始请求
    Request request() {
      return originalRequest;
    }

    // 获取实例本身
    RealCall get() {
      return RealCall.this;
    }

    // 执行方法 从拦截器链中取出响应结果
    // 如果取出过程异常，回调事件监听器的callFailed方法和结果响应回调的onFailure方法
    // 接着判断Call是否被取消：如果被取消，调用结果响应回调的onFailure方法；否则，调用onResponse方法回传结果
    // 如果在上述（上一行描述）的回调方法中抛出异常，打印错误信息
    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        Response response = getResponseWithInterceptorChain();
        if (retryAndFollowUpInterceptor.isCanceled()) {
          signalledCallback = true;
          responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          responseCallback.onResponse(RealCall.this, response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          eventListener.callFailed(RealCall.this, e);
          responseCallback.onFailure(RealCall.this, e);
        }
      } finally {
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * 返回一个描述当前Call的字符串。它不包括完整的URL，因为这样会包含敏感信息。
   *
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  String toLoggableString() {
    return (isCanceled() ? "canceled " : "")
        + (forWebSocket ? "web socket" : "call")
        + " to " + redactedUrl();
  }

  // 修改过的url，不含敏感信息
  String redactedUrl() {
    return originalRequest.url().redact();
  }

  // 从拦截器链中取出响应结果
  Response getResponseWithInterceptorChain() throws IOException {
    // 构建一个完整的拦截器堆栈（默认拦截器+自定义拦截器）
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());
    interceptors.add(retryAndFollowUpInterceptor);
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    interceptors.add(new CacheInterceptor(client.internalCache()));
    interceptors.add(new ConnectInterceptor(client));
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }
    interceptors.add(new CallServerInterceptor(forWebSocket));

    Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0,
        originalRequest, this, eventListener, client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis());

    // 责任链模式处理，最后返回响应结果
    return chain.proceed(originalRequest);
  }
}
