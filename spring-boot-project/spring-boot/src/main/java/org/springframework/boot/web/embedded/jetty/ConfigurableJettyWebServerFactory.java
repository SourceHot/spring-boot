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

package org.springframework.boot.web.embedded.jetty;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.ThreadPool;

import org.springframework.boot.web.server.ConfigurableWebServerFactory;

/**
 * {@link ConfigurableWebServerFactory} for Jetty-specific features.
 *
 * @author Brian Clozel
 * @since 2.0.0
 * @see JettyServletWebServerFactory
 * @see JettyReactiveWebServerFactory
 */
public interface ConfigurableJettyWebServerFactory extends ConfigurableWebServerFactory {

	/**
	 * Set the number of acceptor threads to use.
	 * 设置接受者线程数
	 * @param acceptors the number of acceptor threads to use
	 */
	void setAcceptors(int acceptors);

	/**
	 * Set the {@link ThreadPool} that should be used by the {@link Server}. If set to
	 * {@code null} (default), the {@link Server} creates a {@link ThreadPool} implicitly.
	 * 设置线程池
	 * @param threadPool the ThreadPool to be used
	 */
	void setThreadPool(ThreadPool threadPool);

	/**
	 * Set the number of selector threads to use.
	 * 设置选择器线程数量
	 * @param selectors the number of selector threads to use
	 */
	void setSelectors(int selectors);

	/**
	 * Set if x-forward-* headers should be processed.
	 * 设置是否需要处理x-forward-*请求头
	 * @param useForwardHeaders if x-forward headers should be used
	 */
	void setUseForwardHeaders(boolean useForwardHeaders);

	/**
	 * Add {@link JettyServerCustomizer}s that will be applied to the {@link Server}
	 * before it is started.
	 * 添加JettyServerCustomizer
	 * @param customizers the customizers to add
	 */
	void addServerCustomizers(JettyServerCustomizer... customizers);

}
