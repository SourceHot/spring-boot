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

package org.springframework.boot.web.embedded.jetty;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.ThreadPool;

import org.springframework.boot.web.reactive.server.AbstractReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.client.reactive.JettyResourceFactory;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.JettyHttpHandlerAdapter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * {@link ReactiveWebServerFactory} that can be used to create {@link JettyWebServer}s.
 *
 * @author Brian Clozel
 * @since 2.0.0
 */
public class JettyReactiveWebServerFactory extends AbstractReactiveWebServerFactory
		implements ConfigurableJettyWebServerFactory {

	private static final Log logger = LogFactory.getLog(JettyReactiveWebServerFactory.class);

	/**
	 * The number of acceptor threads to use.
	 */
	private int acceptors = -1;

	/**
	 * The number of selector threads to use.
	 */
	private int selectors = -1;

	private boolean useForwardHeaders;

	private Set<JettyServerCustomizer> jettyServerCustomizers = new LinkedHashSet<>();

	private JettyResourceFactory resourceFactory;

	private ThreadPool threadPool;

	/**
	 * Create a new {@link JettyServletWebServerFactory} instance.
	 */
	public JettyReactiveWebServerFactory() {
	}

	/**
	 * Create a new {@link JettyServletWebServerFactory} that listens for requests using
	 * the specified port.
	 * @param port the port to listen on
	 */
	public JettyReactiveWebServerFactory(int port) {
		super(port);
	}

	@Override
	public void setUseForwardHeaders(boolean useForwardHeaders) {
		this.useForwardHeaders = useForwardHeaders;
	}

	@Override
	public void setAcceptors(int acceptors) {
		this.acceptors = acceptors;
	}

	@Override
	public WebServer getWebServer(HttpHandler httpHandler) {
		// 创建Jetty的Http请求处理适配器
		JettyHttpHandlerAdapter servlet = new JettyHttpHandlerAdapter(httpHandler);
		// 创建jetty-server对象
		Server server = createJettyServer(servlet);
		// 创建jetty-webserver对象
		return new JettyWebServer(server, getPort() >= 0);
	}

	@Override
	public void addServerCustomizers(JettyServerCustomizer... customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.jettyServerCustomizers.addAll(Arrays.asList(customizers));
	}

	/**
	 * Sets {@link JettyServerCustomizer}s that will be applied to the {@link Server}
	 * before it is started. Calling this method will replace any existing customizers.
	 * @param customizers the Jetty customizers to apply
	 */
	public void setServerCustomizers(Collection<? extends JettyServerCustomizer> customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.jettyServerCustomizers = new LinkedHashSet<>(customizers);
	}

	/**
	 * Returns a mutable collection of Jetty {@link JettyServerCustomizer}s that will be
	 * applied to the {@link Server} before it is created.
	 * @return the Jetty customizers
	 */
	public Collection<JettyServerCustomizer> getServerCustomizers() {
		return this.jettyServerCustomizers;
	}

	/**
	 * Returns a Jetty {@link ThreadPool} that should be used by the {@link Server}.
	 * @return a Jetty {@link ThreadPool} or {@code null}
	 */
	public ThreadPool getThreadPool() {
		return this.threadPool;
	}

	@Override
	public void setThreadPool(ThreadPool threadPool) {
		this.threadPool = threadPool;
	}

	@Override
	public void setSelectors(int selectors) {
		this.selectors = selectors;
	}

	/**
	 * Set the {@link JettyResourceFactory} to get the shared resources from.
	 * @param resourceFactory the server resources
	 * @since 2.1.0
	 */
	public void setResourceFactory(JettyResourceFactory resourceFactory) {
		this.resourceFactory = resourceFactory;
	}

	protected JettyResourceFactory getResourceFactory() {
		return this.resourceFactory;
	}

	protected Server createJettyServer(JettyHttpHandlerAdapter servlet) {
		// 确认端口
		int port = Math.max(getPort(), 0);
		// 确认网络地址
		InetSocketAddress address = new InetSocketAddress(getAddress(), port);
		// 创建server对象
		Server server = new Server(getThreadPool());
		// 设置连接器
		server.addConnector(createConnector(address, server));
		server.setStopTimeout(0);
		// 创建servlet持有器
		ServletHolder servletHolder = new ServletHolder(servlet);
		// 设置支持异步
		servletHolder.setAsyncSupported(true);
		// 创建servlet上下文持有器
		ServletContextHandler contextHandler = new ServletContextHandler(server, "/", false, false);
		contextHandler.addServlet(servletHolder, "/");
		// 为server对象设置处理器
		server.setHandler(addHandlerWrappers(contextHandler));
		JettyReactiveWebServerFactory.logger.info("Server initialized with port: " + port);
		// 设置ssl
		if (getSsl() != null && getSsl().isEnabled()) {
			customizeSsl(server, address);
		}
		// 自定义配置处理
		for (JettyServerCustomizer customizer : getServerCustomizers()) {
			customizer.customize(server);
		}
		// 允许使用forward请求头的情况下进行处理
		if (this.useForwardHeaders) {
			new ForwardHeadersCustomizer().customize(server);
		}
		// 关闭类型是等待处理完成后停止进行处理
		if (getShutdown() == Shutdown.GRACEFUL) {
			StatisticsHandler statisticsHandler = new StatisticsHandler();
			statisticsHandler.setHandler(server.getHandler());
			server.setHandler(statisticsHandler);
		}
		// 返回server对象
		return server;
	}

	private AbstractConnector createConnector(InetSocketAddress address, Server server) {
		ServerConnector connector;
		JettyResourceFactory resourceFactory = getResourceFactory();
		if (resourceFactory != null) {
			connector = new ServerConnector(server, resourceFactory.getExecutor(), resourceFactory.getScheduler(),
					resourceFactory.getByteBufferPool(), this.acceptors, this.selectors, new HttpConnectionFactory());
		}
		else {
			connector = new ServerConnector(server, this.acceptors, this.selectors);
		}
		connector.setHost(address.getHostString());
		connector.setPort(address.getPort());
		for (ConnectionFactory connectionFactory : connector.getConnectionFactories()) {
			if (connectionFactory instanceof HttpConfiguration.ConnectionFactory) {
				((HttpConfiguration.ConnectionFactory) connectionFactory).getHttpConfiguration()
						.setSendServerVersion(false);
			}
		}
		return connector;
	}

	private Handler addHandlerWrappers(Handler handler) {
		if (getCompression() != null && getCompression().getEnabled()) {
			handler = applyWrapper(handler, JettyHandlerWrappers.createGzipHandlerWrapper(getCompression()));
		}
		if (StringUtils.hasText(getServerHeader())) {
			handler = applyWrapper(handler, JettyHandlerWrappers.createServerHeaderHandlerWrapper(getServerHeader()));
		}
		return handler;
	}

	private Handler applyWrapper(Handler handler, HandlerWrapper wrapper) {
		wrapper.setHandler(handler);
		return wrapper;
	}

	private void customizeSsl(Server server, InetSocketAddress address) {
		new SslServerCustomizer(address, getSsl(), getSslStoreProvider(), getHttp2()).customize(server);
	}

}
