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

/**
 * Call对象是一个为执行而准备的请求。一个Call可以被取消。由于这个对象表示一个单独的请求/相应对，因此不能二次执行
 *
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 */
public interface Call extends Cloneable {
  // 返回原始请求，即Request对象，它被用来构造Call
  /** Returns the original request that initiated this call. */
  Request request();

  /**
   * 立即执行请求，并且阻塞直到响应被处理或请求出错。
   * 为了避免资源泄露，调用者应该关闭Response，以便可以使ResponseBody被关闭
   * 代码如下：
   *   // 确保response（和底层的response body）被关闭
   *   try (Response response = client.newCall(request).execute()) {
   *     ...
   *   }
   *
   * 调用者应该通过response的body方法读取响应体，为了避免资源泄露，调用者必须关闭Response或ResponseBody
   * 需要注意的是，传输层的成功（成功接收一个HTTP响应代码、响应头和响应体）不一定表示应用层的成功：
   * 响应可能依旧代表一个不让人愉快的HTTP响应，比如404或者500
   *
   * 可能抛出的异常：
   * IO异常：如果由于被取消、连接问题或者连接超时导致了请求不能被执行。因为网络在交换过程中可能失败，比如远程服务器在失败之前接受了请求。
   * 状态异常：当Call已经被执行过。
   *
   *
   * Invokes the request immediately, and blocks until the response can be processed or is in
   * error.
   *
   * <p>To avoid leaking resources callers should close the {@link Response} which in turn will
   * close the underlying {@link ResponseBody}.
   *
   * <pre>{@code
   *
   *   // ensure the response (and underlying response body) is closed
   *   try (Response response = client.newCall(request).execute()) {
   *     ...
   *   }
   *
   * }</pre>
   *
   * <p>The caller may read the response body with the response's {@link Response#body} method. To
   * avoid leaking resources callers must {@linkplain ResponseBody close the response body} or the
   * Response.
   *
   * <p>Note that transport-layer success (receiving a HTTP response code, headers and body) does
   * not necessarily indicate application-layer success: {@code response} may still indicate an
   * unhappy HTTP response code like 404 or 500.
   *
   * @throws IOException if the request could not be executed due to cancellation, a connectivity
   * problem or timeout. Because networks can fail during an exchange, it is possible that the
   * remote server accepted the request before the failure.
   * @throws IllegalStateException when the call has already been executed.
   */
  Response execute() throws IOException;

  /**
   * 安排请求将在未来的某个时刻执行。
   * OkHttpClient的调度者对象dispatcher定义了请求将在何时被执行：通常会立即执行除非已经有一些其他请求在当前正在被执行。
   * 这个实例后续将返回一个回调，这个回调带有一个HTTP结果响应或者一个失败
   *
   * 可能抛出的异常：
   * 状态异常：当Call已经被执行过。
   *
   * Schedules the request to be executed at some point in the future.
   *
   * <p>The {@link OkHttpClient#dispatcher dispatcher} defines when the request will run: usually
   * immediately unless there are several other requests currently being executed.
   *
   * <p>This client will later call back {@code responseCallback} with either an HTTP response or a
   * failure exception.
   *
   * @throws IllegalStateException when the call has already been executed.
   */
  void enqueue(Callback responseCallback);

  /**
   * 如果可能的话，取消请求。已经完成的请求不可以被取消。
   */
  /** Cancels the request, if possible. Requests that are already complete cannot be canceled. */
  void cancel();

  /**
   * 如果Call已经执行或者入队了，返回真。
   * 多次执行一个Call是违法操作
   *
   * Returns true if this call has been either {@linkplain #execute() executed} or {@linkplain
   * #enqueue(Callback) enqueued}. It is an error to execute a call more than once.
   */
  boolean isExecuted();

  /**
   * 是否已经取消
   */
  boolean isCanceled();

  /**
   * 克隆
   * 创建一个新的、完全相同的Call，它可以被执行或入队即使被克隆的Call已经存在。
   *
   * Create a new, identical call to this one which can be enqueued or executed even if this call
   * has already been.
   */
  Call clone();

  // 接口，Call工厂。
  // 基于Request创建一个Call实例
  interface Factory {
    Call newCall(Request request);
  }
}
