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

import java.io.File;
import java.util.Collection;

import io.undertow.Undertow.Builder;

import org.springframework.boot.web.server.ConfigurableWebServerFactory;

/**
 * {@link ConfigurableWebServerFactory} for Undertow-specific features.
 *
 * @author Brian Clozel
 * @since 2.0.0
 * @see UndertowServletWebServerFactory
 * @see UndertowReactiveWebServerFactory
 */
public interface ConfigurableUndertowWebServerFactory extends ConfigurableWebServerFactory {

	/**
	 * Set {@link UndertowBuilderCustomizer}s that should be applied to the Undertow
	 * {@link Builder}. Calling this method will replace any existing customizers.
	 * 
	 * 设置UndertowBuilderCustomizer集合
	 * @param customizers the customizers to set
	 * @since 2.3.0
	 */
	void setBuilderCustomizers(Collection<? extends UndertowBuilderCustomizer> customizers);

	/**
	 * Add {@link UndertowBuilderCustomizer}s that should be used to customize the
	 * Undertow {@link Builder}.
	 * 添加UndertowBuilderCustomizer集合
	 * @param customizers the customizers to add
	 */
	void addBuilderCustomizers(UndertowBuilderCustomizer... customizers);

	/**
	 * Set the buffer size.
	 * 设置缓存大小
	 * @param bufferSize buffer size
	 */
	void setBufferSize(Integer bufferSize);

	/**
	 * Set the number of IO Threads.
	 * 设置io线程数
	 * @param ioThreads number of IO Threads
	 */
	void setIoThreads(Integer ioThreads);

	/**
	 * Set the number of Worker Threads.
	 * 设置工作线程数
	 * @param workerThreads number of Worker Threads
	 */
	void setWorkerThreads(Integer workerThreads);

	/**
	 * Set whether direct buffers should be used.
	 * 设置是否应使用直接缓冲区
	 * @param useDirectBuffers whether direct buffers should be used
	 */
	void setUseDirectBuffers(Boolean useDirectBuffers);

	/**
	 * Set the access log directory.
	 * 设置访问日志目录
	 * @param accessLogDirectory access log directory
	 */
	void setAccessLogDirectory(File accessLogDirectory);

	/**
	 * Set the access log pattern.
	 * 设置访问日志模式
	 * @param accessLogPattern access log pattern
	 */
	void setAccessLogPattern(String accessLogPattern);

	/**
	 * Set the access log prefix.
	 * 设置访问日志前缀
	 * @param accessLogPrefix log prefix
	 */
	void setAccessLogPrefix(String accessLogPrefix);

	/**
	 * Set the access log suffix.
	 * 设置访问日志后缀
	 * @param accessLogSuffix access log suffix
	 */
	void setAccessLogSuffix(String accessLogSuffix);

	/**
	 * Set whether access logs are enabled.
	 * 设置是否启用访问日志
	 * @param accessLogEnabled whether access logs are enabled
	 */
	void setAccessLogEnabled(boolean accessLogEnabled);

	/**
	 * Set whether access logs rotation is enabled.
	 * 设置是否启用访问日志轮换。
	 * @param accessLogRotate whether access logs rotation is enabled
	 */
	void setAccessLogRotate(boolean accessLogRotate);

	/**
	 * Set if x-forward-* headers should be processed.
	 * 设置是否处理 x-forward-*请求头
	 * @param useForwardHeaders if x-forward headers should be used
	 */
	void setUseForwardHeaders(boolean useForwardHeaders);

}
