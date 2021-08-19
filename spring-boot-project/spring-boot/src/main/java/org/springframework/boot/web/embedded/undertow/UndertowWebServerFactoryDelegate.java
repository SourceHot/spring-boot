/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.boot.web.embedded.undertow;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.Undertow.Builder;
import io.undertow.UndertowOptions;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.*;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.InetAddress;
import java.util.*;

/**
 * Delegate class used by {@link UndertowServletWebServerFactory} and
 * {@link UndertowReactiveWebServerFactory}.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 */
class UndertowWebServerFactoryDelegate {
	/**
	 * UndertowBuilderCustomizer接口集合
	 */
	private Set<UndertowBuilderCustomizer> builderCustomizers = new LinkedHashSet<>();
	/**
	 * 缓冲区大小
	 */
	private Integer bufferSize;
	/**
	 * IO线程数量
	 */
	private Integer ioThreads;
	/**
	 * 工作线程数量
	 */
	private Integer workerThreads;
	/**
	 * 是否开启直接缓存区
	 */
	private Boolean directBuffers;
	/**
	 * 访问日志目录
	 */
	private File accessLogDirectory;
	/**
	 * 访问日志模式
	 */
	private String accessLogPattern;
	/**
	 * 访问日志前缀
	 */
	private String accessLogPrefix;
	/**
	 * 访问日志后缀
	 */
	private String accessLogSuffix;
	/**
	 * 是否启用访问日志
	 */
	private boolean accessLogEnabled = false;
	/**
	 * 是否启用访问日志轮换
	 */
	private boolean accessLogRotate = true;
	/**
	 * 是否处理 x-forward-*请求头
	 */
	private boolean useForwardHeaders;

	static List<HttpHandlerFactory> createHttpHandlerFactories(Compression compression, boolean useForwardHeaders,
															   String serverHeader, Shutdown shutdown, HttpHandlerFactory... initialHttpHandlerFactories) {
		List<HttpHandlerFactory> factories = new ArrayList<>(Arrays.asList(initialHttpHandlerFactories));
		if (compression != null && compression.getEnabled()) {
			factories.add(new CompressionHttpHandlerFactory(compression));
		}
		if (useForwardHeaders) {
			factories.add(Handlers::proxyPeerAddress);
		}
		if (StringUtils.hasText(serverHeader)) {
			factories.add((next) -> Handlers.header(next, "Server", serverHeader));
		}
		if (shutdown == Shutdown.GRACEFUL) {
			factories.add(Handlers::gracefulShutdown);
		}
		return factories;
	}

	void addBuilderCustomizers(UndertowBuilderCustomizer... customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.builderCustomizers.addAll(Arrays.asList(customizers));
	}

	Collection<UndertowBuilderCustomizer> getBuilderCustomizers() {
		return this.builderCustomizers;
	}

	void setBuilderCustomizers(Collection<? extends UndertowBuilderCustomizer> customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.builderCustomizers = new LinkedHashSet<>(customizers);
	}

	void setBufferSize(Integer bufferSize) {
		this.bufferSize = bufferSize;
	}

	void setIoThreads(Integer ioThreads) {
		this.ioThreads = ioThreads;
	}

	void setWorkerThreads(Integer workerThreads) {
		this.workerThreads = workerThreads;
	}

	void setUseDirectBuffers(Boolean directBuffers) {
		this.directBuffers = directBuffers;
	}

	void setAccessLogDirectory(File accessLogDirectory) {
		this.accessLogDirectory = accessLogDirectory;
	}

	void setAccessLogPattern(String accessLogPattern) {
		this.accessLogPattern = accessLogPattern;
	}

	String getAccessLogPrefix() {
		return this.accessLogPrefix;
	}

	void setAccessLogPrefix(String accessLogPrefix) {
		this.accessLogPrefix = accessLogPrefix;
	}

	void setAccessLogSuffix(String accessLogSuffix) {
		this.accessLogSuffix = accessLogSuffix;
	}

	boolean isAccessLogEnabled() {
		return this.accessLogEnabled;
	}

	void setAccessLogEnabled(boolean accessLogEnabled) {
		this.accessLogEnabled = accessLogEnabled;
	}

	void setAccessLogRotate(boolean accessLogRotate) {
		this.accessLogRotate = accessLogRotate;
	}

	boolean isUseForwardHeaders() {
		return this.useForwardHeaders;
	}

	void setUseForwardHeaders(boolean useForwardHeaders) {
		this.useForwardHeaders = useForwardHeaders;
	}

	Builder createBuilder(AbstractConfigurableWebServerFactory factory) {
		Ssl ssl = factory.getSsl();
		InetAddress address = factory.getAddress();
		int port = factory.getPort();
		Builder builder = Undertow.builder();
		if (this.bufferSize != null) {
			builder.setBufferSize(this.bufferSize);
		}
		if (this.ioThreads != null) {
			builder.setIoThreads(this.ioThreads);
		}
		if (this.workerThreads != null) {
			builder.setWorkerThreads(this.workerThreads);
		}
		if (this.directBuffers != null) {
			builder.setDirectBuffers(this.directBuffers);
		}
		if (ssl != null && ssl.isEnabled()) {
			new SslBuilderCustomizer(factory.getPort(), address, ssl, factory.getSslStoreProvider()).customize(builder);
			Http2 http2 = factory.getHttp2();
			if (http2 != null) {
				builder.setServerOption(UndertowOptions.ENABLE_HTTP2, http2.isEnabled());
			}
		} else {
			builder.addHttpListener(port, (address != null) ? address.getHostAddress() : "0.0.0.0");
		}
		builder.setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 0);
		for (UndertowBuilderCustomizer customizer : this.builderCustomizers) {
			customizer.customize(builder);
		}
		return builder;
	}

	List<HttpHandlerFactory> createHttpHandlerFactories(AbstractConfigurableWebServerFactory webServerFactory,
														HttpHandlerFactory... initialHttpHandlerFactories) {
		List<HttpHandlerFactory> factories = createHttpHandlerFactories(webServerFactory.getCompression(),
				this.useForwardHeaders, webServerFactory.getServerHeader(), webServerFactory.getShutdown(),
				initialHttpHandlerFactories);
		if (isAccessLogEnabled()) {
			factories.add(new AccessLogHttpHandlerFactory(this.accessLogDirectory, this.accessLogPattern,
					this.accessLogPrefix, this.accessLogSuffix, this.accessLogRotate));
		}
		return factories;
	}

}
