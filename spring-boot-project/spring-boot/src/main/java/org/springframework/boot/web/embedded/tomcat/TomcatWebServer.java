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

package org.springframework.boot.web.embedded.tomcat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.naming.NamingException;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.naming.ContextBindings;

import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.util.Assert;

/**
 * {@link WebServer} that can be used to control a Tomcat web server. Usually this class
 * should be created using the {@link TomcatReactiveWebServerFactory} or
 * {@link TomcatServletWebServerFactory}, but not directly.
 *
 * @author Brian Clozel
 * @author Kristine Jetzke
 * @since 2.0.0
 */
public class TomcatWebServer implements WebServer {

	private static final Log logger = LogFactory.getLog(TomcatWebServer.class);

	/**
	 * 容器计数器
	 */
	private static final AtomicInteger containerCounter = new AtomicInteger(-1);

	/**
	 * 锁
	 */
	private final Object monitor = new Object();
	/**
	 * 服务和连接器列表映射
	 */
	private final Map<Service, Connector[]> serviceConnectors = new HashMap<>();
	/**
	 * tomcat对象
	 */
	private final Tomcat tomcat;
	/**
	 * 是否自动启动
	 */
	private final boolean autoStart;
	/**
	 * tomcat的优雅关闭
	 */
	private final GracefulShutdown gracefulShutdown;
	/**
	 * 是否开启
	 */
	private volatile boolean started;

	/**
	 * Create a new {@link TomcatWebServer} instance.
	 * @param tomcat the underlying Tomcat server
	 */
	public TomcatWebServer(Tomcat tomcat) {
		this(tomcat, true);
	}

	/**
	 * Create a new {@link TomcatWebServer} instance.
	 * @param tomcat the underlying Tomcat server
	 * @param autoStart if the server should be started
	 */
	public TomcatWebServer(Tomcat tomcat, boolean autoStart) {
		this(tomcat, autoStart, Shutdown.IMMEDIATE);
	}

	/**
	 * Create a new {@link TomcatWebServer} instance.
	 * @param tomcat the underlying Tomcat server
	 * @param autoStart if the server should be started
	 * @param shutdown type of shutdown supported by the server
	 * @since 2.3.0
	 */
	public TomcatWebServer(Tomcat tomcat, boolean autoStart, Shutdown shutdown) {
		Assert.notNull(tomcat, "Tomcat Server must not be null");
		this.tomcat = tomcat;
		this.autoStart = autoStart;
		this.gracefulShutdown = (shutdown == Shutdown.GRACEFUL) ? new GracefulShutdown(tomcat) : null;
		initialize();
	}

	/**
	 * 初始化方法
	 * @throws WebServerException
	 */
	private void initialize() throws WebServerException {
		logger.info("Tomcat initialized with port(s): " + getPortsDescription(false));
		synchronized (this.monitor) {
			try {
				// 添加容器计数器并且给引擎设置名称
				addInstanceIdToEngineName();

				// 寻找上下文
				Context context = findContext();
				// 上下文中添加生命周期的处理策略，此时的生命周期是启动,在启动时移除所有的连接对象,
				// 注意移除的是tomcat中的连接对象，移除内容会被放入到成员变量serviceConnectors中
				context.addLifecycleListener((event) -> {
					if (context.equals(event.getSource()) && Lifecycle.START_EVENT.equals(event.getType())) {
						// Remove service connectors so that protocol binding doesn't
						// happen when the service is started.
						removeServiceConnectors();
					}
				});

				// Start the server to trigger initialization listeners
				// tomcat 启动
				this.tomcat.start();

				// We can re-throw failure exception directly in the main thread
				// 尝试性抛出异常,不一定会抛出异常
				rethrowDeferredStartupExceptions();

				try {
					// 绑定上下文，token和类加载器
					ContextBindings.bindClassLoader(context, context.getNamingToken(), getClass().getClassLoader());
				}
				catch (NamingException ex) {
					// Naming is not enabled. Continue
				}

				// Unlike Jetty, all Tomcat threads are daemon threads. We create a
				// blocking non-daemon to stop immediate shutdown
				// 创建非守护线程来防止立即关闭
				startDaemonAwaitThread();
			}
			catch (Exception ex) {
				// 停止tomcat
				stopSilently();
				// 摧毁tomcat
				destroySilently();
				// 抛出异常
				throw new WebServerException("Unable to start embedded Tomcat", ex);
			}
		}
	}

	/**
	 * 寻找上下文
	 */
	private Context findContext() {
		for (Container child : this.tomcat.getHost().findChildren()) {
			if (child instanceof Context) {
				return (Context) child;
			}
		}
		throw new IllegalStateException("The host does not contain a Context");
	}

	/**
	 * 设置引擎id
	 */
	private void addInstanceIdToEngineName() {
		int instanceId = containerCounter.incrementAndGet();
		if (instanceId > 0) {
			Engine engine = this.tomcat.getEngine();
			engine.setName(engine.getName() + "-" + instanceId);
		}
	}

	/**
	 * 移除服务连接
	 */
	private void removeServiceConnectors() {
		for (Service service : this.tomcat.getServer().findServices()) {
			Connector[] connectors = service.findConnectors().clone();
			this.serviceConnectors.put(service, connectors);
			for (Connector connector : connectors) {
				service.removeConnector(connector);
			}
		}
	}

	/**
	 * 抛出启动异常
	 */
	private void rethrowDeferredStartupExceptions() throws Exception {
		Container[] children = this.tomcat.getHost().findChildren();
		for (Container container : children) {
			// 容器类型是TomcatEmbeddedContext
			if (container instanceof TomcatEmbeddedContext) {
				TomcatStarter tomcatStarter = ((TomcatEmbeddedContext) container).getStarter();
				// 获取TomcatStarter不为空
				if (tomcatStarter != null) {
					Exception exception = tomcatStarter.getStartUpException();
					// TomcatStarter中存在启动异常
					if (exception != null) {
						throw exception;
					}
				}
			}
			// 容器状态不是STARTED抛出异常
			if (!LifecycleState.STARTED.equals(container.getState())) {
				throw new IllegalStateException(container + " failed to start");
			}
		}
	}

	/**
	 * 启动非守护进程
	 */
	private void startDaemonAwaitThread() {
		Thread awaitThread = new Thread("container-" + (containerCounter.get())) {

			@Override
			public void run() {
				TomcatWebServer.this.tomcat.getServer().await();
			}

		};
		awaitThread.setContextClassLoader(getClass().getClassLoader());
		awaitThread.setDaemon(false);
		awaitThread.start();
	}

	@Override
	public void start() throws WebServerException {
		// 锁
		synchronized (this.monitor) {
			// 如果已经启动不做操作
			if (this.started) {
				return;
			}
			try {
				// 处理tomcat对象中的Service和成员变量serviceConnectors进行对比移除或暂停连接器
				addPreviouslyRemovedConnectors();
				// 获取连接器
				Connector connector = this.tomcat.getConnector();
				// 连接器存在并且是自动启动的
				if (connector != null && this.autoStart) {
					// 执行延迟加载操作
					performDeferredLoadOnStartup();
				}
				// 检查连接器是否启动
				checkThatConnectorsHaveStarted();
				this.started = true;
				logger.info("Tomcat started on port(s): " + getPortsDescription(true) + " with context path '"
						+ getContextPath() + "'");
			} catch (ConnectorStartFailedException ex) {
				// 关闭tomcat
				stopSilently();
				throw ex;
			} catch (Exception ex) {
				// 端口绑定异常处理
				PortInUseException.throwIfPortBindingException(ex, () -> this.tomcat.getConnector().getPort());
				throw new WebServerException("Unable to start embedded Tomcat server", ex);
			} finally {
				// 寻找上下文
				Context context = findContext();
				// 解绑上下文
				ContextBindings.unbindClassLoader(context, context.getNamingToken(), getClass().getClassLoader());
			}
		}
	}

	private void checkThatConnectorsHaveStarted() {
		checkConnectorHasStarted(this.tomcat.getConnector());
		for (Connector connector : this.tomcat.getService().findConnectors()) {
			checkConnectorHasStarted(connector);
		}
	}

	private void checkConnectorHasStarted(Connector connector) {
		if (LifecycleState.FAILED.equals(connector.getState())) {
			throw new ConnectorStartFailedException(connector.getPort());
		}
	}

	private void stopSilently() {
		try {
			stopTomcat();
		}
		catch (LifecycleException ex) {
			// Ignore
		}
	}

	private void destroySilently() {
		try {
			this.tomcat.destroy();
		}
		catch (LifecycleException ex) {
			// Ignore
		}
	}

	private void stopTomcat() throws LifecycleException {
		if (Thread.currentThread().getContextClassLoader() instanceof TomcatEmbeddedWebappClassLoader) {
			Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
		}
		this.tomcat.stop();
	}

	/**
	 *
	 */
	private void addPreviouslyRemovedConnectors() {
		Service[] services = this.tomcat.getServer().findServices();
		for (Service service : services) {
			Connector[] connectors = this.serviceConnectors.get(service);
			if (connectors != null) {
				for (Connector connector : connectors) {
					service.addConnector(connector);
					if (!this.autoStart) {
						// 暂停连接器
						stopProtocolHandler(connector);
					}
				}
				this.serviceConnectors.remove(service);
			}
		}
	}

	private void stopProtocolHandler(Connector connector) {
		try {
			connector.getProtocolHandler().stop();
		}
		catch (Exception ex) {
			logger.error("Cannot pause connector: ", ex);
		}
	}

	private void performDeferredLoadOnStartup() {
		try {
			for (Container child : this.tomcat.getHost().findChildren()) {
				if (child instanceof TomcatEmbeddedContext) {
					((TomcatEmbeddedContext) child).deferredLoadOnStartup();
				}
			}
		}
		catch (Exception ex) {
			if (ex instanceof WebServerException) {
				throw (WebServerException) ex;
			}
			throw new WebServerException("Unable to start embedded Tomcat connectors", ex);
		}
	}

	Map<Service, Connector[]> getServiceConnectors() {
		return this.serviceConnectors;
	}

	@Override
	public void stop() throws WebServerException {
		// 锁
		synchronized (this.monitor) {
			boolean wasStarted = this.started;
			try {
				this.started = false;
				try {
					if (this.gracefulShutdown != null) {
						// 流产标记为true
						this.gracefulShutdown.abort();
					}
					// 停止tomcat
					stopTomcat();
					// 摧毁tomcat
					this.tomcat.destroy();
				} catch (LifecycleException ex) {
					// swallow and continue
				}
			} catch (Exception ex) {
				throw new WebServerException("Unable to stop embedded Tomcat", ex);
			} finally {
				if (wasStarted) {
					// 容器计数器减1
					containerCounter.decrementAndGet();
				}
			}
		}
	}

	private String getPortsDescription(boolean localPort) {
		StringBuilder ports = new StringBuilder();
		for (Connector connector : this.tomcat.getService().findConnectors()) {
			if (ports.length() != 0) {
				ports.append(' ');
			}
			int port = localPort ? connector.getLocalPort() : connector.getPort();
			ports.append(port).append(" (").append(connector.getScheme()).append(')');
		}
		return ports.toString();
	}

	@Override
	public int getPort() {
		Connector connector = this.tomcat.getConnector();
		if (connector != null) {
			return connector.getLocalPort();
		}
		return -1;
	}

	private String getContextPath() {
		return Arrays.stream(this.tomcat.getHost().findChildren()).filter(TomcatEmbeddedContext.class::isInstance)
				.map(TomcatEmbeddedContext.class::cast).map(TomcatEmbeddedContext::getPath)
				.collect(Collectors.joining(" "));
	}

	/**
	 * Returns access to the underlying Tomcat server.
	 * @return the Tomcat server
	 */
	public Tomcat getTomcat() {
		return this.tomcat;
	}

	@Override
	public void shutDownGracefully(GracefulShutdownCallback callback) {
		if (this.gracefulShutdown == null) {
			callback.shutdownComplete(GracefulShutdownResult.IMMEDIATE);
			return;
		}
		this.gracefulShutdown.shutDownGracefully(callback);
	}

}
