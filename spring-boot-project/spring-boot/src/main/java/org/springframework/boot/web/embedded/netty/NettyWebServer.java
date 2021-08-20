/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.web.embedded.netty;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.unix.Errors.NativeIoException;
import io.netty.util.concurrent.DefaultEventExecutor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.netty.ChannelBindException;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;

import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.util.Assert;

/**
 * {@link WebServer} that can be used to control a Reactor Netty web server. Usually this
 * class should be created using the {@link NettyReactiveWebServerFactory} and not
 * directly.
 *
 * @author Brian Clozel
 * @author Madhura Bhave
 * @author Andy Wilkinson
 * @since 2.0.0
 */
public class NettyWebServer implements WebServer {

	/**
	 * Permission denied error code from {@code errno.h}.
	 * 权限不足代码
	 */
	private static final int ERROR_NO_EACCES = -13;
	/**
	 * 恒真对象
	 */
	private static final Predicate<HttpServerRequest> ALWAYS = (request) -> true;
	private static final Log logger = LogFactory.getLog(NettyWebServer.class);
	/**
	 * http服务
	 */
	private final HttpServer httpServer;
	/**
	 * 处理器
	 */
	private final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler;
	/**
	 * 生命周期超时时间
	 */
	private final Duration lifecycleTimeout;
	/**
	 * 优雅关闭
	 */
	private final GracefulShutdown gracefulShutdown;
	/**
	 * 路由提供者集合
	 */
	private List<NettyRouteProvider> routeProviders = Collections.emptyList();
	/**
	 * 服务信息对象
	 */
	private volatile DisposableServer disposableServer;

	public NettyWebServer(HttpServer httpServer, ReactorHttpHandlerAdapter handlerAdapter, Duration lifecycleTimeout,
						  Shutdown shutdown) {
		Assert.notNull(httpServer, "HttpServer must not be null");
		Assert.notNull(handlerAdapter, "HandlerAdapter must not be null");
		this.lifecycleTimeout = lifecycleTimeout;
		this.handler = handlerAdapter;
		this.httpServer = httpServer.channelGroup(new DefaultChannelGroup(new DefaultEventExecutor()));
		this.gracefulShutdown = (shutdown == Shutdown.GRACEFUL) ? new GracefulShutdown(() -> this.disposableServer)
				: null;
	}

	public void setRouteProviders(List<NettyRouteProvider> routeProviders) {
		this.routeProviders = routeProviders;
	}

	@Override
	public void start() throws WebServerException {
		// 服务信息对象为空的情况下处理
		if (this.disposableServer == null) {
			try {
				// 开始http服务
				this.disposableServer = startHttpServer();
			} catch (Exception ex) {
				PortInUseException.ifCausedBy(ex, ChannelBindException.class, (bindException) -> {
					if (bindException.localPort() > 0 && !isPermissionDenied(bindException.getCause())) {
						throw new PortInUseException(bindException.localPort(), ex);
					}
				});
				throw new WebServerException("Unable to start Netty", ex);
			}
			// 服务信息对象不为空输出日志
			if (this.disposableServer != null) {
				logger.info("Netty started" + getStartedOnMessage(this.disposableServer));
			}
			// 启动非守护线程，放置程序关闭
			startDaemonAwaitThread(this.disposableServer);
		}
	}

	private String getStartedOnMessage(DisposableServer server) {
		StringBuilder message = new StringBuilder();
		tryAppend(message, "port %s", () -> server.port());
		tryAppend(message, "path %s", () -> server.path());
		return (message.length() > 0) ? " on " + message : "";
	}

	private void tryAppend(StringBuilder message, String format, Supplier<Object> supplier) {
		try {
			Object value = supplier.get();
			message.append((message.length() != 0) ? " " : "");
			message.append(String.format(format, value));
		} catch (UnsupportedOperationException ex) {
		}
	}

	DisposableServer startHttpServer() {
		HttpServer server = this.httpServer;
		if (this.routeProviders.isEmpty()) {
			server = server.handle(this.handler);
		} else {
			server = server.route(this::applyRouteProviders);
		}
		if (this.lifecycleTimeout != null) {
			return server.bindNow(this.lifecycleTimeout);
		}
		return server.bindNow();
	}

	private boolean isPermissionDenied(Throwable bindExceptionCause) {
		try {
			if (bindExceptionCause instanceof NativeIoException) {
				return ((NativeIoException) bindExceptionCause).expectedErr() == ERROR_NO_EACCES;
			}
		} catch (Throwable ex) {
		}
		return false;
	}

	@Override
	public void shutDownGracefully(GracefulShutdownCallback callback) {
		if (this.gracefulShutdown == null) {
			callback.shutdownComplete(GracefulShutdownResult.IMMEDIATE);
			return;
		}
		this.gracefulShutdown.shutDownGracefully(callback);
	}

	private void applyRouteProviders(HttpServerRoutes routes) {
		for (NettyRouteProvider provider : this.routeProviders) {
			routes = provider.apply(routes);
		}
		routes.route(ALWAYS, this.handler);
	}

	private void startDaemonAwaitThread(DisposableServer disposableServer) {
		Thread awaitThread = new Thread("server") {

			@Override
			public void run() {
				disposableServer.onDispose().block();
			}

		};
		awaitThread.setContextClassLoader(getClass().getClassLoader());
		awaitThread.setDaemon(false);
		awaitThread.start();
	}

	@Override
	public void stop() throws WebServerException {
		// 服务信息对象不为空的情况下处理
		if (this.disposableServer != null) {
			// 优雅关闭程序存在的情况下将进行终止操作
			if (this.gracefulShutdown != null) {
				this.gracefulShutdown.abort();
			}
			try {
				// 阻塞模式关闭
				if (this.lifecycleTimeout != null) {
					this.disposableServer.disposeNow(this.lifecycleTimeout);
				} else {
					this.disposableServer.disposeNow();
				}
			} catch (IllegalStateException ex) {
				// Continue
			}
			// 服务信息设置为null
			this.disposableServer = null;
		}
	}

	@Override
	public int getPort() {
		// 服务信息对象不为空的情况下获取
		if (this.disposableServer != null) {
			try {
				// 从服务信息对象中获取端口
				return this.disposableServer.port();
			} catch (UnsupportedOperationException ex) {
				return -1;
			}
		}
		return -1;
	}

}
