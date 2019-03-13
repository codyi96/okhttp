/*
 * Copyright (C) 2012 Square, Inc.
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

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.cache.InternalCache;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.tls.CertificateChainCleaner;
import okhttp3.internal.tls.OkHostnameVerifier;
import okhttp3.internal.ws.RealWebSocket;
import okio.Sink;
import okio.Source;

import static okhttp3.internal.Util.assertionError;
import static okhttp3.internal.Util.checkDuration;

/**
 * 这是一个创建网络请求的工厂，它可以被用于发送http请求和接受请求结果
 * Factory for {@linkplain Call calls}, which can be used to send HTTP requests and read their
 * responses.
 *
 * OkHttpClients实例应该对外公开
 * <h3>OkHttpClients should be shared</h3>
 *
 * 当你需要创建一个单例并且重复使用它发起http请求时，OkHttp能够表现出很好的性能。这是因为每个实例拥有自己的连接池和线程池。
 * 复用链接和线程可以缩减等待时间并且节省内存。相反的，如果为每个请求创建实例，会造成空闲资源池的浪费
 * <p>OkHttp performs best when you create a single {@code OkHttpClient} instance and reuse it for
 * all of your HTTP calls. This is because each client holds its own connection pool and thread
 * pools. Reusing connections and threads reduces latency and saves memory. Conversely, creating a
 * client for each request wastes resources on idle pools.
 *
 * <p>Use {@code new OkHttpClient()} to create a shared instance with the default settings:
 * <pre>   {@code
 *
 *   // The singleton HTTP client.
 *   public final OkHttpClient client = new OkHttpClient();
 * }</pre>
 *
 * <p>Or use {@code new OkHttpClient.Builder()} to create a shared instance with custom settings:
 * <pre>   {@code
 *
 *   // The singleton HTTP client.
 *   public final OkHttpClient client = new OkHttpClient.Builder()
 *       .addInterceptor(new HttpLoggingInterceptor())
 *       .cache(new Cache(cacheDir, cacheSize))
 *       .build();
 * }</pre>
 *
 * <h3>Customize your client with newBuilder()</h3>
 *
 * 你可以通过newBuilder定制一个OkHttpClient实例。它创建了一个实例来使用相同的连接池、线程池以及配置。
 * 通过这样的创建方法可以为不同的需求去配置派生客户端。
 * <p>You can customize a shared OkHttpClient instance with {@link #newBuilder()}. This builds a
 * client that shares the same connection pool, thread pools, and configuration. Use the builder
 * methods to configure the derived client for a specific purpose.
 *
 * <p>This example shows a call with a short 500 millisecond timeout: <pre>   {@code
 *
 *   OkHttpClient eagerClient = client.newBuilder()
 *       .readTimeout(500, TimeUnit.MILLISECONDS)
 *       .build();
 *   Response response = eagerClient.newCall(request).execute();
 * }</pre>
 *
 * 你不需要特意关闭实例
 * <h3>Shutdown isn't necessary</h3>
 *
 * 当持续空闲时，所有已创建的线程和连接都将被自动释放。但是如果你的应用需要积极释放无用的资源，你也可以手动释放。
 * <p>The threads and connections that are held will be released automatically if they remain idle.
 * But if you are writing a application that needs to aggressively release unused resources you may
 * do so.
 *
 * 你可以通过ExecutorService.shutdown关闭调度者的执行服务。一旦调度者的执行服务被关闭，后续的请求将被拒绝。
 * <p>Shutdown the dispatcher's executor service with {@link ExecutorService#shutdown shutdown()}.
 * This will also cause future calls to the client to be rejected. <pre>   {@code
 *
 *     client.dispatcher().executorService().shutdown();
 * }</pre>
 *
 * 你可以通过ConnectionPool.evictAll方法清空连接池。需要注意的是，连接池的守护线程不会立即退出。
 * <p>Clear the connection pool with {@link ConnectionPool#evictAll() evictAll()}. Note that the
 * connection pool's daemon thread may not exit immediately. <pre>   {@code
 *
 *     client.connectionPool().evictAll();
 * }</pre>
 *
 * 如果你的实例有一个缓存，你需要调用Cache.close。需要注意的是，你不可以在一个已经关闭的缓存中创建请求，这将会导致请求崩溃
 * <p>If your client has a cache, call {@link Cache#close close()}. Note that it is an error to
 * create calls against a cache that is closed, and doing so will cause the call to crash.
 * <pre>   {@code
 *
 *     client.cache().close();
 * }</pre>
 *
 * 在HTTP/2方面，OkHttp也使用了守护线程。如果长期闲置，它们将自动退出
 * <p>OkHttp also uses daemon threads for HTTP/2 connections. These will exit automatically if they
 * remain idle.
 */
public class OkHttpClient implements Cloneable, Call.Factory, WebSocket.Factory {
  static final List<Protocol> DEFAULT_PROTOCOLS = Util.immutableList(
      Protocol.HTTP_2, Protocol.HTTP_1_1);

  static final List<ConnectionSpec> DEFAULT_CONNECTION_SPECS = Util.immutableList(
      ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT);

  static {
    Internal.instance = new Internal() {
      @Override public void addLenient(Headers.Builder builder, String line) {
        builder.addLenient(line);
      }

      @Override public void addLenient(Headers.Builder builder, String name, String value) {
        builder.addLenient(name, value);
      }

      @Override public void setCache(OkHttpClient.Builder builder, InternalCache internalCache) {
        builder.setInternalCache(internalCache);
      }

      @Override public boolean connectionBecameIdle(
          ConnectionPool pool, RealConnection connection) {
        return pool.connectionBecameIdle(connection);
      }

      @Override public RealConnection get(ConnectionPool pool, Address address,
          StreamAllocation streamAllocation, Route route) {
        return pool.get(address, streamAllocation, route);
      }

      @Override public boolean equalsNonHost(Address a, Address b) {
        return a.equalsNonHost(b);
      }

      @Override public Socket deduplicate(
          ConnectionPool pool, Address address, StreamAllocation streamAllocation) {
        return pool.deduplicate(address, streamAllocation);
      }

      @Override public void put(ConnectionPool pool, RealConnection connection) {
        pool.put(connection);
      }

      @Override public RouteDatabase routeDatabase(ConnectionPool connectionPool) {
        return connectionPool.routeDatabase;
      }

      @Override public int code(Response.Builder responseBuilder) {
        return responseBuilder.code;
      }

      @Override
      public void apply(ConnectionSpec tlsConfiguration, SSLSocket sslSocket, boolean isFallback) {
        tlsConfiguration.apply(sslSocket, isFallback);
      }

      @Override public boolean isInvalidHttpUrlHost(IllegalArgumentException e) {
        return e.getMessage().startsWith(HttpUrl.Builder.INVALID_HOST);
      }

      @Override public StreamAllocation streamAllocation(Call call) {
        return ((RealCall) call).streamAllocation();
      }

      @Override public Call newWebSocketCall(OkHttpClient client, Request originalRequest) {
        return RealCall.newRealCall(client, originalRequest, true);
      }
    };
  }

  final Dispatcher dispatcher;
  final @Nullable Proxy proxy;
  final List<Protocol> protocols;
  final List<ConnectionSpec> connectionSpecs;
  final List<Interceptor> interceptors;
  final List<Interceptor> networkInterceptors;
  final EventListener.Factory eventListenerFactory;
  final ProxySelector proxySelector;
  final CookieJar cookieJar;
  final @Nullable Cache cache;
  final @Nullable InternalCache internalCache;
  final SocketFactory socketFactory;
  final @Nullable SSLSocketFactory sslSocketFactory;
  final @Nullable CertificateChainCleaner certificateChainCleaner;
  final HostnameVerifier hostnameVerifier;
  final CertificatePinner certificatePinner;
  final Authenticator proxyAuthenticator;
  final Authenticator authenticator;
  final ConnectionPool connectionPool;
  final Dns dns;
  final boolean followSslRedirects;
  final boolean followRedirects;
  final boolean retryOnConnectionFailure;
  final int connectTimeout;
  final int readTimeout;
  final int writeTimeout;
  final int pingInterval;

  /**
   * 无参构造函数
   */
  public OkHttpClient() {
    this(new Builder());
  }

  /**
   * 有参构造函数，传入Builder对象
   * Builder类详情见下述定义
   */
  OkHttpClient(Builder builder) {
    this.dispatcher = builder.dispatcher;
    this.proxy = builder.proxy;
    this.protocols = builder.protocols;
    this.connectionSpecs = builder.connectionSpecs;
    this.interceptors = Util.immutableList(builder.interceptors);
    this.networkInterceptors = Util.immutableList(builder.networkInterceptors);
    this.eventListenerFactory = builder.eventListenerFactory;
    this.proxySelector = builder.proxySelector;
    this.cookieJar = builder.cookieJar;
    this.cache = builder.cache;
    this.internalCache = builder.internalCache;
    this.socketFactory = builder.socketFactory;

    /**
     * SSL协议是TLS协议的前身，它们都是位于某种可靠传输协议（eg. TCP）之上的加密协议
     * ConnectionSpec包含四种类型：
     * RESTRICTED_TLS（isTLS=true）
     * MODERN_TLS（isTLS=true）
     * COMPATIBLE_TLS（isTLS=true）
     * CLEARTEXT（isTLS=false）
     */
    // 是否TLS协议判断，如果有一项配置为TLS协议，则为真
    boolean isTLS = false;
    for (ConnectionSpec spec : connectionSpecs) {
      isTLS = isTLS || spec.isTls();
    }

    // 如果用户定义了ssl工厂或非TLS协议，则赋值
    if (builder.sslSocketFactory != null || !isTLS) {
      this.sslSocketFactory = builder.sslSocketFactory;
      this.certificateChainCleaner = builder.certificateChainCleaner;
    } else {
      // 创建证书信任管理类，校验证书是否过期和证书签名是否合法
      // 然后以此创建ssl工厂
      X509TrustManager trustManager = Util.platformTrustManager();
      this.sslSocketFactory = newSslSocketFactory(trustManager);
      this.certificateChainCleaner = CertificateChainCleaner.get(trustManager);
    }

    // 如果ssl工厂不为空，根据平台配置ssl工厂
    if (sslSocketFactory != null) {
      Platform.get().configureSslSocketFactory(sslSocketFactory);
    }

    this.hostnameVerifier = builder.hostnameVerifier;
    this.certificatePinner = builder.certificatePinner.withCertificateChainCleaner(
        certificateChainCleaner);
    this.proxyAuthenticator = builder.proxyAuthenticator;
    this.authenticator = builder.authenticator;
    this.connectionPool = builder.connectionPool;
    this.dns = builder.dns;
    this.followSslRedirects = builder.followSslRedirects;
    this.followRedirects = builder.followRedirects;
    this.retryOnConnectionFailure = builder.retryOnConnectionFailure;
    this.connectTimeout = builder.connectTimeout;
    this.readTimeout = builder.readTimeout;
    this.writeTimeout = builder.writeTimeout;
    this.pingInterval = builder.pingInterval;

    // 用户定义的应用拦截器有效性判断（不包含null）
    if (interceptors.contains(null)) {
      throw new IllegalStateException("Null interceptor: " + interceptors);
    }
    // 用户定义的网络拦截器有效性判断（不包含null）
    if (networkInterceptors.contains(null)) {
      throw new IllegalStateException("Null network interceptor: " + networkInterceptors);
    }
  }

  // 静态方法，创建ssl工厂
  private static SSLSocketFactory newSslSocketFactory(X509TrustManager trustManager) {
    try {
      SSLContext sslContext = Platform.get().getSSLContext();
      sslContext.init(null, new TrustManager[] { trustManager }, null);
      return sslContext.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw assertionError("No System TLS", e); // The system has no TLS. Just give up.
    }
  }

  /** 连接超时等待时间 */
  /** Default connect timeout (in milliseconds). */
  public int connectTimeoutMillis() {
    return connectTimeout;
  }

  /** 读超时等待时间 */
  /** Default read timeout (in milliseconds). */
  public int readTimeoutMillis() {
    return readTimeout;
  }

  /** 写超时等待时间 */
  /** Default write timeout (in milliseconds). */
  public int writeTimeoutMillis() {
    return writeTimeout;
  }

  /** socket连接间隔时间 */
  /** Web socket ping interval (in milliseconds). */
  public int pingIntervalMillis() {
    return pingInterval;
  }

  /** 代理 */
  public Proxy proxy() {
    return proxy;
  }

  /** 代理选择器 */
  public ProxySelector proxySelector() {
    return proxySelector;
  }

  /** cookie管理器 */
  public CookieJar cookieJar() {
    return cookieJar;
  }

  /** 缓存 */
  public @Nullable Cache cache() {
    return cache;
  }

  /** 内部缓存
   *  当有缓存时，取缓存中的内部缓存
   */
  InternalCache internalCache() {
    return cache != null ? cache.internalCache : internalCache;
  }

  /** dns */
  public Dns dns() {
    return dns;
  }

  /** socket工厂 */
  public SocketFactory socketFactory() {
    return socketFactory;
  }

  /** ssl工厂 */
  public SSLSocketFactory sslSocketFactory() {
    return sslSocketFactory;
  }

  /** 主机名验证者 */
  public HostnameVerifier hostnameVerifier() {
    return hostnameVerifier;
  }

  /** 证书识别者规定了被信任的证书和机构 */
  public CertificatePinner certificatePinner() {
    return certificatePinner;
  }

  /** 身份验证者 */
  public Authenticator authenticator() {
    return authenticator;
  }

  /** 代理的身份验证者 */
  public Authenticator proxyAuthenticator() {
    return proxyAuthenticator;
  }

  /** 连接池 */
  public ConnectionPool connectionPool() {
    return connectionPool;
  }

  /** 是否遵循ssl重定向 */
  public boolean followSslRedirects() {
    return followSslRedirects;
  }

  /** 是否遵循重定向 */
  public boolean followRedirects() {
    return followRedirects;
  }

  /** 是否在连接失败时重试 */
  public boolean retryOnConnectionFailure() {
    return retryOnConnectionFailure;
  }

  /** 调度者 */
  public Dispatcher dispatcher() {
    return dispatcher;
  }

  /** 协议集合 */
  public List<Protocol> protocols() {
    return protocols;
  }

  /** 连接配置 */
  public List<ConnectionSpec> connectionSpecs() {
    return connectionSpecs;
  }

  /**
   * 返回一个不变的拦截器集合，这些拦截器用来观察每一个Call的周期：从连接建立之前到响应数据被选择后
   * Returns an immutable list of interceptors that observe the full span of each call: from before
   * the connection is established (if any) until after the response source is selected (either the
   * origin server, cache, or both).
   */
  public List<Interceptor> interceptors() {
    return interceptors;
  }

  /**
   * 返回一个不变的拦截器集合，这些拦截器用来观察一个单一的网络请求和响应。
   * 这些拦截器会调用Interceptor.Chain#proceed一次：网络拦截器不可短路或重复请求
   * Returns an immutable list of interceptors that observe a single network request and response.
   * These interceptors must call {@link Interceptor.Chain#proceed} exactly once: it is an error for
   * a network interceptor to short-circuit or repeat a network request.
   */
  public List<Interceptor> networkInterceptors() {
    return networkInterceptors;
  }

  /** 事件监听者工厂 */
  public EventListener.Factory eventListenerFactory() {
    return eventListenerFactory;
  }

  /**
   * 准备一个可能会在未来某一时刻执行的请求
   * Prepares the {@code request} to be executed at some point in the future.
   */
  @Override public Call newCall(Request request) {
    return RealCall.newRealCall(this, request, false /* for web socket */);
  }

  /**
   * 用请求建立一个新的socket连接
   * Uses {@code request} to connect a new web socket.
   */
  @Override public WebSocket newWebSocket(Request request, WebSocketListener listener) {
    RealWebSocket webSocket = new RealWebSocket(request, listener, new Random(), pingInterval);
    webSocket.connect(this);
    return webSocket;
  }

  /** 构建者，用于构建实例 */
  public Builder newBuilder() {
    return new Builder(this);
  }

  public static final class Builder {
    Dispatcher dispatcher;
    @Nullable Proxy proxy;
    List<Protocol> protocols;
    List<ConnectionSpec> connectionSpecs;
    final List<Interceptor> interceptors = new ArrayList<>();
    final List<Interceptor> networkInterceptors = new ArrayList<>();
    EventListener.Factory eventListenerFactory;
    ProxySelector proxySelector;
    CookieJar cookieJar;
    @Nullable Cache cache;
    @Nullable InternalCache internalCache;
    SocketFactory socketFactory;
    @Nullable SSLSocketFactory sslSocketFactory;
    @Nullable CertificateChainCleaner certificateChainCleaner;
    HostnameVerifier hostnameVerifier;
    CertificatePinner certificatePinner;
    Authenticator proxyAuthenticator;
    Authenticator authenticator;
    ConnectionPool connectionPool;
    Dns dns;
    boolean followSslRedirects;
    boolean followRedirects;
    boolean retryOnConnectionFailure;
    int connectTimeout;
    int readTimeout;
    int writeTimeout;
    int pingInterval;

    // 无参构造，传入默认值
    public Builder() {
      // 新建调度者对象，详见类注释
      dispatcher = new Dispatcher();
      // 默认协议集合 默认为HTTP2、HTTP1
      protocols = DEFAULT_PROTOCOLS;
      // 连接配置 默认为MODERN_TLS、CLEARTEXT
      connectionSpecs = DEFAULT_CONNECTION_SPECS;
      // 事件监听者工厂
      eventListenerFactory = EventListener.factory(EventListener.NONE);
      // 获取代理选择器 默认为一个系统级的代理选择器
      proxySelector = ProxySelector.getDefault();
      // Cookie管理者 默认无Cookie
      cookieJar = CookieJar.NO_COOKIES;
      // socket工厂
      socketFactory = SocketFactory.getDefault();
      // 主机名验证者 默认为无参构造创建的验证者实例
      hostnameVerifier = OkHostnameVerifier.INSTANCE;
      // 证书识别者规定了被信任的证书和机构 默认为空
      certificatePinner = CertificatePinner.DEFAULT;
      // 代理的身份验证者
      proxyAuthenticator = Authenticator.NONE;
      // 身份验证者
      authenticator = Authenticator.NONE;
      // 连接池
      connectionPool = new ConnectionPool();
      // dns 大多数自定义dns实现都委托给这个实例
      dns = Dns.SYSTEM;
      followSslRedirects = true;
      followRedirects = true;
      retryOnConnectionFailure = true;
      connectTimeout = 10_000;
      readTimeout = 10_000;
      writeTimeout = 10_000;
      pingInterval = 0;
    }

    Builder(OkHttpClient okHttpClient) {
      this.dispatcher = okHttpClient.dispatcher;
      this.proxy = okHttpClient.proxy;
      this.protocols = okHttpClient.protocols;
      this.connectionSpecs = okHttpClient.connectionSpecs;
      this.interceptors.addAll(okHttpClient.interceptors);
      this.networkInterceptors.addAll(okHttpClient.networkInterceptors);
      this.eventListenerFactory = okHttpClient.eventListenerFactory;
      this.proxySelector = okHttpClient.proxySelector;
      this.cookieJar = okHttpClient.cookieJar;
      this.internalCache = okHttpClient.internalCache;
      this.cache = okHttpClient.cache;
      this.socketFactory = okHttpClient.socketFactory;
      this.sslSocketFactory = okHttpClient.sslSocketFactory;
      this.certificateChainCleaner = okHttpClient.certificateChainCleaner;
      this.hostnameVerifier = okHttpClient.hostnameVerifier;
      this.certificatePinner = okHttpClient.certificatePinner;
      this.proxyAuthenticator = okHttpClient.proxyAuthenticator;
      this.authenticator = okHttpClient.authenticator;
      this.connectionPool = okHttpClient.connectionPool;
      this.dns = okHttpClient.dns;
      this.followSslRedirects = okHttpClient.followSslRedirects;
      this.followRedirects = okHttpClient.followRedirects;
      this.retryOnConnectionFailure = okHttpClient.retryOnConnectionFailure;
      this.connectTimeout = okHttpClient.connectTimeout;
      this.readTimeout = okHttpClient.readTimeout;
      this.writeTimeout = okHttpClient.writeTimeout;
      this.pingInterval = okHttpClient.pingInterval;
    }

    /**
     * 为新的连接设置默认的连接超时等待时间。这个值必须在1到2^31-1毫秒之间
     * 当使用TCP套接字连接到目标主机时，这个等待时间被使用，默认值为10秒
     * 参数列表： 超时时间、时间单位
     * Sets the default connect timeout for new connections. A value of 0 means no timeout,
     * otherwise values must be between 1 and {@link Integer#MAX_VALUE} when converted to
     * milliseconds.
     *
     * <p>The connectTimeout is applied when connecting a TCP socket to the target host.
     * The default value is 10 seconds.
     */
    public Builder connectTimeout(long timeout, TimeUnit unit) {
      connectTimeout = checkDuration("timeout", timeout, unit);
      return this;
    }

    /**
     * 为新的连接设置默认的读超时等待时间。这个值必须在1到2^31-1毫秒之间
     * 这个等待时间被使用在TCP套接字和Source.Response的读IO操作上，默认值为10秒
     * 参数列表： 超时时间、时间单位
     * Sets the default read timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and {@link Integer#MAX_VALUE} when converted to milliseconds.
     *
     * <p>The read timeout is applied to both the TCP socket and for individual read IO operations
     * including on {@link Source} of the {@link Response}. The default value is 10 seconds.
     *
     * @see Socket#setSoTimeout(int)
     * @see Source#timeout()
     */
    public Builder readTimeout(long timeout, TimeUnit unit) {
      readTimeout = checkDuration("timeout", timeout, unit);
      return this;
    }

    /**
     * 为新的连接设置默认的写超时等待时间。这个值必须在1到2^31-1毫秒之间
     * 这个等待时间被使用在写IO操作上，默认值为10秒
     * 参数列表： 超时时间、时间单位
     * Sets the default write timeout for new connections. A value of 0 means no timeout, otherwise
     * values must be between 1 and {@link Integer#MAX_VALUE} when converted to milliseconds.
     *
     * <p>The write timeout is applied for individual write IO operations.
     * The default value is 10 seconds.
     *
     * @see Sink#timeout()
     */
    public Builder writeTimeout(long timeout, TimeUnit unit) {
      writeTimeout = checkDuration("timeout", timeout, unit);
      return this;
    }

    /**
     * 设置由客户端发起的HTTP/2和网页套接字之间的连通间隔。我们可以使用它自动发送ping帧直到连接失败或关闭。
     * 它保持连接的活性并且检测连接失败。
     * 如果服务器没有在间隔时间内对ping请求回传pong响应，客户端将假定连接丢失。
     * 在网页套接字连接时，如果连接被取消或者连接的监听者提示失败时将发生上述情况。
     * 在HTTP/2连接时，如果连接被关闭或者任意调用崩溃时将发生上述情况。
     * 默认值为0，即禁用客户端的ping。
     * 参数列表： 间隔时间、时间单位
     * Sets the interval between HTTP/2 and web socket pings initiated by this client. Use this to
     * automatically send ping frames until either the connection fails or it is closed. This keeps
     * the connection alive and may detect connectivity failures.
     *
     * <p>If the server does not respond to each ping with a pong within {@code interval}, this
     * client will assume that connectivity has been lost. When this happens on a web socket the
     * connection is canceled and its listener is {@linkplain WebSocketListener#onFailure notified
     * of the failure}. When it happens on an HTTP/2 connection the connection is closed and any
     * calls it is carrying {@linkplain java.io.IOException will fail with an IOException}.
     *
     * <p>The default value of 0 disables client-initiated pings.
     */
    public Builder pingInterval(long interval, TimeUnit unit) {
      pingInterval = checkDuration("interval", interval, unit);
      return this;
    }

    /**
     * 设置HTTP代理用于该实例创建的连接。它比代理选择者proxySelector优先，即仅当proxy为默认值（空）时，代理选择者生效。
     * 默认值：Proxy.NO_PROXY
     * Sets the HTTP proxy that will be used by connections created by this client. This takes
     * precedence over {@link #proxySelector}, which is only honored when this proxy is null (which
     * it is by default). To disable proxy use completely, call {@code proxy(Proxy.NO_PROXY)}.
     */
    public Builder proxy(@Nullable Proxy proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * 设置代理选择器规则，当代理为空时生效。代理选择器将返回多个代理：这种情况下，将按序尝试知道成功建立连接。
     * 如果不设置此项，将使用默认值
     * Sets the proxy selection policy to be used if no {@link #proxy proxy} is specified
     * explicitly. The proxy selector may return multiple proxies; in that case they will be tried
     * in sequence until a successful connection is established.
     *
     * <p>If unset, the {@link ProxySelector#getDefault() system-wide default} proxy selector will
     * be used.
     */
    public Builder proxySelector(ProxySelector proxySelector) {
      this.proxySelector = proxySelector;
      return this;
    }

    /**
     * 设置一个管理者，它可以接受来自HTTP响应的Cooike并且提供Cookie给发出的HTTP请求。
     * 如果不设置，将不接受也不提供Cookie
     * Sets the handler that can accept cookies from incoming HTTP responses and provides cookies to
     * outgoing HTTP requests.
     *
     * <p>If unset, {@linkplain CookieJar#NO_COOKIES no cookies} will be accepted nor provided.
     */
    public Builder cookieJar(CookieJar cookieJar) {
      if (cookieJar == null) throw new NullPointerException("cookieJar == null");
      this.cookieJar = cookieJar;
      return this;
    }

    /** 设置响应缓存用以读写已经缓存的响应结果（内部）
     *  内外部缓存不可同时存在
     */
    /** Sets the response cache to be used to read and write cached responses. */
    void setInternalCache(@Nullable InternalCache internalCache) {
      this.internalCache = internalCache;
      this.cache = null;
    }

    /** 设置响应缓存用以读写已经缓存的响应结果
     *  内外部缓存不可同时存在
     */
    /** Sets the response cache to be used to read and write cached responses. */
    public Builder cache(@Nullable Cache cache) {
      this.cache = cache;
      this.internalCache = null;
      return this;
    }

    /**
     * 设置DNS服务用以查询主机名对应的ip地址
     * Sets the DNS service used to lookup IP addresses for hostnames.
     *
     * <p>If unset, the {@link Dns#SYSTEM system-wide default} DNS will be used.
     */
    public Builder dns(Dns dns) {
      if (dns == null) throw new NullPointerException("dns == null");
      this.dns = dns;
      return this;
    }

    /**
     * 设置Socket工厂用以创建连接。Okhttp仅用无参的方法createSocket去创建未连接的套接字。
     * 通过重写这个方法，例如，允许套接字绑定到一个指定的本地地址
     * Sets the socket factory used to create connections. OkHttp only uses the parameterless {@link
     * SocketFactory#createSocket() createSocket()} method to create unconnected sockets. Overriding
     * this method, e. g., allows the socket to be bound to a specific local address.
     *
     * <p>If unset, the {@link SocketFactory#getDefault() system-wide default} socket factory will
     * be used.
     */
    public Builder socketFactory(SocketFactory socketFactory) {
      if (socketFactory == null) throw new NullPointerException("socketFactory == null");
      this.socketFactory = socketFactory;
      return this;
    }

    /**
     * 设置一个Socket工厂用以安全的HTTPS连接
     * @被废弃的方法： SSLSocketFactory没有暴露它的X509TrustManager（这是一个证书信任管理类），因此需要用
     * 反射的方法获取证书信任管理对象。我们应该更倾向于调用sslSocketFactory(SSLSocketFactory, X509TrustManager)
     * Sets the socket factory used to secure HTTPS connections. If unset, the system default will
     * be used.
     *
     * @deprecated {@code SSLSocketFactory} does not expose its {@link X509TrustManager}, which is
     *     a field that OkHttp needs to build a clean certificate chain. This method instead must
     *     use reflection to extract the trust manager. Applications should prefer to call {@link
     *     #sslSocketFactory(SSLSocketFactory, X509TrustManager)}, which avoids such reflection.
     */
    public Builder sslSocketFactory(SSLSocketFactory sslSocketFactory) {
      if (sslSocketFactory == null) throw new NullPointerException("sslSocketFactory == null");
      this.sslSocketFactory = sslSocketFactory;
      this.certificateChainCleaner = Platform.get().buildCertificateChainCleaner(sslSocketFactory);
      return this;
    }

    /**
     * 设置ssl工厂和证书信任管理类用以安全的HTTPS连接。
     * 大多数情况下我们不用调用这个方法，而可以使用系统默认的值。这些类包含特殊的优化，如果自定义很可能丢失这些优化。
     * 如果需要的话，你可以参照下面的代码，创建并且配置自己的默认值。
     *
     * Sets the socket factory and trust manager used to secure HTTPS connections. If unset, the
     * system defaults will be used.
     *
     * <p>Most applications should not call this method, and instead use the system defaults. Those
     * classes include special optimizations that can be lost if the implementations are decorated.
     *
     * <p>If necessary, you can create and configure the defaults yourself with the following code:
     *
     * <pre>   {@code
     *
     *   TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
     *       TrustManagerFactory.getDefaultAlgorithm());
     *   trustManagerFactory.init((KeyStore) null);
     *   TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
     *   if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
     *     throw new IllegalStateException("Unexpected default trust managers:"
     *         + Arrays.toString(trustManagers));
     *   }
     *   X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
     *
     *   SSLContext sslContext = SSLContext.getInstance("TLS");
     *   sslContext.init(null, new TrustManager[] { trustManager }, null);
     *   SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
     *
     *   OkHttpClient client = new OkHttpClient.Builder()
     *       .sslSocketFactory(sslSocketFactory, trustManager)
     *       .build();
     * }</pre>
     */
    public Builder sslSocketFactory(
        SSLSocketFactory sslSocketFactory, X509TrustManager trustManager) {
      if (sslSocketFactory == null) throw new NullPointerException("sslSocketFactory == null");
      if (trustManager == null) throw new NullPointerException("trustManager == null");
      this.sslSocketFactory = sslSocketFactory;
      this.certificateChainCleaner = CertificateChainCleaner.get(trustManager);
      return this;
    }

    /**
     * 设置验证者用以确认HTTPS连接中应用于请求主机名的响应证书
     * Sets the verifier used to confirm that response certificates apply to requested hostnames for
     * HTTPS connections.
     *
     * <p>If unset, a default hostname verifier will be used.
     */
    public Builder hostnameVerifier(HostnameVerifier hostnameVerifier) {
      if (hostnameVerifier == null) throw new NullPointerException("hostnameVerifier == null");
      this.hostnameVerifier = hostnameVerifier;
      return this;
    }

    /**
     * 设置证书识别者用以判断哪些证书被信任。
     * 默认情况下，HTTPS连接只依赖sslSocketFactory去建立信任。
     * 识别证书避免了信任证书颁发机构的需要。
     * Sets the certificate pinner that constrains which certificates are trusted. By default HTTPS
     * connections rely on only the {@link #sslSocketFactory SSL socket factory} to establish trust.
     * Pinning certificates avoids the need to trust certificate authorities.
     */
    public Builder certificatePinner(CertificatePinner certificatePinner) {
      if (certificatePinner == null) throw new NullPointerException("certificatePinner == null");
      this.certificatePinner = certificatePinner;
      return this;
    }

    /**
     * 设置证书颁发机构用以响应服务器认证，如果不设置，将不进行身份验证
     * Sets the authenticator used to respond to challenges from origin servers. Use {@link
     * #proxyAuthenticator} to set the authenticator for proxy servers.
     *
     * <p>If unset, the {@linkplain Authenticator#NONE no authentication will be attempted}.
     */
    public Builder authenticator(Authenticator authenticator) {
      if (authenticator == null) throw new NullPointerException("authenticator == null");
      this.authenticator = authenticator;
      return this;
    }

    /**
     * 设置证书颁发机构用以响应代理服务器认证，如果不设置，将不进行身份验证
     * Sets the authenticator used to respond to challenges from proxy servers. Use {@link
     * #authenticator} to set the authenticator for origin servers.
     *
     * <p>If unset, the {@linkplain Authenticator#NONE no authentication will be attempted}.
     */
    public Builder proxyAuthenticator(Authenticator proxyAuthenticator) {
      if (proxyAuthenticator == null) throw new NullPointerException("proxyAuthenticator == null");
      this.proxyAuthenticator = proxyAuthenticator;
      return this;
    }

    /**
     * 设置连接池，用以重用HTTP、HTTPS连接
     * Sets the connection pool used to recycle HTTP and HTTPS connections.
     *
     * <p>If unset, a new connection pool will be used.
     */
    public Builder connectionPool(ConnectionPool connectionPool) {
      if (connectionPool == null) throw new NullPointerException("connectionPool == null");
      this.connectionPool = connectionPool;
      return this;
    }

    /**
     * 配置实例是否遵循ssl重定向（HTTPS到HTTP 或是 HTTP到HTTPS）
     * 如果不配置，将遵循协议重定向。
     * Configure this client to follow redirects from HTTPS to HTTP and from HTTP to HTTPS.
     *
     * <p>If unset, protocol redirects will be followed. This is different than the built-in {@code
     * HttpURLConnection}'s default.
     */
    public Builder followSslRedirects(boolean followProtocolRedirects) {
      this.followSslRedirects = followProtocolRedirects;
      return this;
    }

    /**
     * 配置实例是否遵循重定向，如果不配置，默认为遵循
     */
    /** Configure this client to follow redirects. If unset, redirects will be followed. */
    public Builder followRedirects(boolean followRedirects) {
      this.followRedirects = followRedirects;
      return this;
    }

    /**
     * 配置实例当遇到连通性问题时是否重试。默认情况下，实例将从下述问题中静默恢复：
     * 1. 无法到达的IP地址：如果URL的主机有多个ip地址，无法到达任何单独的ip不会造成整个请求失败，这可以提升多源服务的可用性
     * 2. 陈旧的集中式连接：重用连接池可以减少请求等待时间，但是这些连接将偶现超时
     * 3. 无法到达的代理服务器：一个代理选择者可以用于尝试序列中的多个代理服务，都失败的情况下会尝试直接连接
     *
     * 将此项设置为false可以避免可能带来破坏的重试请求。这种情况下需要自行在失败时重试请求
     *
     * Configure this client to retry or not when a connectivity problem is encountered. By default,
     * this client silently recovers from the following problems:
     *
     * <ul>
     *   <li><strong>Unreachable IP addresses.</strong> If the URL's host has multiple IP addresses,
     *       failure to reach any individual IP address doesn't fail the overall request. This can
     *       increase availability of multi-homed services.
     *   <li><strong>Stale pooled connections.</strong> The {@link ConnectionPool} reuses sockets
     *       to decrease request latency, but these connections will occasionally time out.
     *   <li><strong>Unreachable proxy servers.</strong> A {@link ProxySelector} can be used to
     *       attempt multiple proxy servers in sequence, eventually falling back to a direct
     *       connection.
     * </ul>
     *
     * Set this to false to avoid retrying requests when doing so is destructive. In this case the
     * calling application should do its own recovery of connectivity failures.
     */
    public Builder retryOnConnectionFailure(boolean retryOnConnectionFailure) {
      this.retryOnConnectionFailure = retryOnConnectionFailure;
      return this;
    }

    /**
     * 设置调度者用于设置规则并且执行异步请求，该项不可为空
     * Sets the dispatcher used to set policy and execute asynchronous requests. Must not be null.
     */
    public Builder dispatcher(Dispatcher dispatcher) {
      if (dispatcher == null) throw new IllegalArgumentException("dispatcher == null");
      this.dispatcher = dispatcher;
      return this;
    }

    /**
     * 配置协议用以实例和远程服务端之间信息传递。默认情况下，该实例倾向于最有效的传输协议，回归更普遍的协议。
     * 实例应用仅仅调用这个方法用以避免特定的兼容性问题，例如，当HTTP/2被启用时，Web服务器将出错。
     * 这是一个进化的集合。未来的版本中将支持过渡协议，而HTTP/1.1传输协议不会被舍弃。
     * 如果多个协议被指定，ALPN被用于协商。协议协商仅仅用于HTTPS URL
     * HTTP/1.1不支持这个集合，请求通过HTTP/1.1被初始化。如果服务端响应了HTTP/1.0，它将通过Response.protocol暴露
     * 参数列表：按照优先顺序排列的协议集合。如果集合包含H2_PRIOR_KNOWLEDGE，则不可再含有其他协议且HTTPS URL不被支持。
     *         否则，集合必须包含HTTP_1_1。同时，集合不可包含null或HTTP_1_0
     *
     * Configure the protocols used by this client to communicate with remote servers. By default
     * this client will prefer the most efficient transport available, falling back to more
     * ubiquitous protocols. Applications should only call this method to avoid specific
     * compatibility problems, such as web servers that behave incorrectly when HTTP/2 is enabled.
     *
     * <p>The following protocols are currently supported:
     *
     * <ul>
     *     <li><a href="http://www.w3.org/Protocols/rfc2616/rfc2616.html">http/1.1</a>
     *     <li><a href="https://tools.ietf.org/html/rfc7540">h2</a>
     *     <li><a href="https://tools.ietf.org/html/rfc7540#section-3.4">h2 with prior knowledge
     *         (cleartext only)</a>
     * </ul>
     *
     * <p><strong>This is an evolving set.</strong> Future releases include support for transitional
     * protocols. The http/1.1 transport will never be dropped.
     *
     * <p>If multiple protocols are specified, <a
     * href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a> will be used to
     * negotiate a transport. Protocol negotiation is only attempted for HTTPS URLs.
     *
     * <p>{@link Protocol#HTTP_1_0} is not supported in this set. Requests are initiated with {@code
     * HTTP/1.1}. If the server responds with {@code HTTP/1.0}, that will be exposed by {@link
     * Response#protocol()}.
     *
     * @param protocols the protocols to use, in order of preference. If the list contains {@link
     *     Protocol#H2_PRIOR_KNOWLEDGE} then that must be the only protocol and HTTPS URLs will not
     *     be supported. Otherwise the list must contain {@link Protocol#HTTP_1_1}. The list must
     *     not contain null or {@link Protocol#HTTP_1_0}.
     */
    public Builder protocols(List<Protocol> protocols) {
      // 创建一个私密的协议集合副本
      // Create a private copy of the list.
      protocols = new ArrayList<>(protocols);

      // 验证集合的每一项是我们所需要的而不是我们禁止的
      // Validate that the list has everything we require and nothing we forbid.

      // H2_PRIOR_KNOWLEDGE 和 HTTP_1_1均不存在————抛出异常
      if (!protocols.contains(Protocol.H2_PRIOR_KNOWLEDGE)
          && !protocols.contains(Protocol.HTTP_1_1)) {
        throw new IllegalArgumentException(
            "protocols must contain h2_prior_knowledge or http/1.1: " + protocols);
      }
      // H2_PRIOR_KNOWLEDGE 之外还有其他协议————抛出异常
      if (protocols.contains(Protocol.H2_PRIOR_KNOWLEDGE) && protocols.size() > 1) {
        throw new IllegalArgumentException(
            "protocols containing h2_prior_knowledge cannot use other protocols: " + protocols);
      }
      // 包含HTTP_1_0————抛出异常
      if (protocols.contains(Protocol.HTTP_1_0)) {
        throw new IllegalArgumentException("protocols must not contain http/1.0: " + protocols);
      }
      // 包含null————抛出异常
      if (protocols.contains(null)) {
        throw new IllegalArgumentException("protocols must not contain null");
      }

      // 移除我们不再支持的SPDY_3协议
      // Remove protocols that we no longer support.
      protocols.remove(Protocol.SPDY_3);

      // 封装协议集合为不可修改的集合
      // Assign as an unmodifiable list. This is effectively immutable.
      this.protocols = Collections.unmodifiableList(protocols);
      return this;
    }

    // 配置连接配置
    public Builder connectionSpecs(List<ConnectionSpec> connectionSpecs) {
      this.connectionSpecs = Util.immutableList(connectionSpecs);
      return this;
    }

    /**
     * 返回一个可修改的拦截器集合，用以观察每个请求的周期：从连接建立之前直到响应被选择之后
     * Returns a modifiable list of interceptors that observe the full span of each call: from
     * before the connection is established (if any) until after the response source is selected
     * (either the origin server, cache, or both).
     */
    public List<Interceptor> interceptors() {
      return interceptors;
    }

    // 添加拦截器
    public Builder addInterceptor(Interceptor interceptor) {
      if (interceptor == null) throw new IllegalArgumentException("interceptor == null");
      interceptors.add(interceptor);
      return this;
    }

    /**
     * 返回一个可修改的网络拦截器集合，用以观察一个单一的网络请求和响应。
     * 这些拦截器会调用Interceptor.Chain#proceed一次：网络拦截器不可短路或重复请求
     * Returns a modifiable list of interceptors that observe a single network request and response.
     * These interceptors must call {@link Interceptor.Chain#proceed} exactly once: it is an error
     * for a network interceptor to short-circuit or repeat a network request.
     */
    public List<Interceptor> networkInterceptors() {
      return networkInterceptors;
    }

    // 添加网络拦截器
    public Builder addNetworkInterceptor(Interceptor interceptor) {
      if (interceptor == null) throw new IllegalArgumentException("interceptor == null");
      networkInterceptors.add(interceptor);
      return this;
    }

    /**
     * 配置单个实例的范围监听者，它将接受所有来自这个实例的解析事件。
     * EventListener用于语义和对监听者实现进行限制
     * Configure a single client scoped listener that will receive all analytic events
     * for this client.
     *
     * @see EventListener for semantics and restrictions on listener implementations.
     */
    public Builder eventListener(EventListener eventListener) {
      if (eventListener == null) throw new NullPointerException("eventListener == null");
      this.eventListenerFactory = EventListener.factory(eventListener);
      return this;
    }

    /**
     * 配置一个工厂，用以提供每个请求的范围监听者，这些监听者将接收实例的解析事件
     * EventListener用于语义和对监听者实现进行限制
     * Configure a factory to provide per-call scoped listeners that will receive analytic events
     * for this client.
     *
     * @see EventListener for semantics and restrictions on listener implementations.
     */
    public Builder eventListenerFactory(EventListener.Factory eventListenerFactory) {
      if (eventListenerFactory == null) {
        throw new NullPointerException("eventListenerFactory == null");
      }
      this.eventListenerFactory = eventListenerFactory;
      return this;
    }

    // 构建方法，创建OkHttpClient对象
    public OkHttpClient build() {
      return new OkHttpClient(this);
    }
  }
}
