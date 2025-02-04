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

package org.springframework.boot.web.servlet.server;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletContext;

import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.WebListenerRegistry;

/**
 * A configurable {@link ServletWebServerFactory}.
 *
 * @author Dave Syer
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Eddú Meléndez
 * @author Brian Clozel
 * @since 2.0.0
 * @see ServletWebServerFactory
 * @see WebServerFactoryCustomizer
 */
public interface ConfigurableServletWebServerFactory
		extends ConfigurableWebServerFactory, ServletWebServerFactory, WebListenerRegistry {

	/**
	 * Sets the context path for the web server. The context should start with a "/"
	 * character but not end with a "/" character. The default context path can be
	 * specified using an empty string.
	 * 设置上下文路径
	 * @param contextPath the contextPath to set
	 */
	void setContextPath(String contextPath);

	/**
	 * Sets the display name of the application deployed in the web server.
		 * 设置显示名称
	 * @param displayName the displayName to set
	 * @since 1.3.0
	 */
	void setDisplayName(String displayName);

	/**
	 * Sets the configuration that will be applied to the container's HTTP session
	 * support.
	 * 设置session对象
	 * @param session the session configuration
	 */
	void setSession(Session session);

	/**
	 * Set if the DefaultServlet should be registered. Defaults to {@code false} since
	 * 2.4.
	 * 设置是否注册默认的servlet
	 * @param registerDefaultServlet if the default servlet should be registered
	 */
	void setRegisterDefaultServlet(boolean registerDefaultServlet);

	/**
	 * Sets the mime-type mappings.
	 * 设置 MIME 类型映射
	 * @param mimeMappings the mime type mappings (defaults to
	 * {@link MimeMappings#DEFAULT})
	 */
	void setMimeMappings(MimeMappings mimeMappings);

	/**
	 * Sets the document root directory which will be used by the web context to serve
	 * static files.
	 * 设置 Web 上下文将使用的文档根目录来提供静态文件。
	 * @param documentRoot the document root or {@code null} if not required
	 */
	void setDocumentRoot(File documentRoot);

	/**
	 * Sets {@link ServletContextInitializer} that should be applied in addition to
	 * {@link ServletWebServerFactory#getWebServer(ServletContextInitializer...)}
	 * parameters. This method will replace any previously set or added initializers.
	 * @param initializers the initializers to set
	 * @see #addInitializers
	 */
	void setInitializers(List<? extends ServletContextInitializer> initializers);

	/**
	 * Add {@link ServletContextInitializer}s to those that should be applied in addition
	 * to {@link ServletWebServerFactory#getWebServer(ServletContextInitializer...)}
	 * parameters.
	 * @param initializers the initializers to add
	 * @see #setInitializers
	 */
	void addInitializers(ServletContextInitializer... initializers);

	/**
	 * Sets the configuration that will be applied to the server's JSP servlet.
	 * @param jsp the JSP servlet configuration
	 */
	void setJsp(Jsp jsp);

	/**
	 * Sets the Locale to Charset mappings.
	 * 用于设置地区和字符编码的绑定关系。
	 * @param localeCharsetMappings the Locale to Charset mappings
	 */
	void setLocaleCharsetMappings(Map<Locale, Charset> localeCharsetMappings);

	/**
	 * Sets the init parameters that are applied to the container's
	 * {@link ServletContext}.
	 * @param initParameters the init parameters
	 */
	void setInitParameters(Map<String, String> initParameters);

}
