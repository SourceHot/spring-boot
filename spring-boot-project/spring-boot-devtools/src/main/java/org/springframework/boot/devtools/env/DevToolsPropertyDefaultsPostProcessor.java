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

package org.springframework.boot.devtools.env;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.devtools.logger.DevToolsLogFactory;
import org.springframework.boot.devtools.restart.Restarter;
import org.springframework.boot.devtools.system.DevToolsEnablementDeducer;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.log.LogMessage;
import org.springframework.util.ClassUtils;

/**
 * {@link EnvironmentPostProcessor} to add properties that make sense when working at
 * development time.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Madhura Bhave
 * @since 1.3.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class DevToolsPropertyDefaultsPostProcessor implements EnvironmentPostProcessor {

	private static final Log logger = DevToolsLogFactory.getLog(DevToolsPropertyDefaultsPostProcessor.class);

	private static final String ENABLED = "spring.devtools.add-properties";

	private static final String WEB_LOGGING = "logging.level.web";

	private static final String[] WEB_ENVIRONMENT_CLASSES = {
			"org.springframework.web.context.ConfigurableWebEnvironment",
			"org.springframework.boot.web.reactive.context.ConfigurableReactiveWebEnvironment" };

	private static final Map<String, Object> PROPERTIES;

	static {
		Map<String, Object> properties = new HashMap<>();
		properties.put("spring.thymeleaf.cache", "false");
		properties.put("spring.freemarker.cache", "false");
		properties.put("spring.groovy.template.cache", "false");
		properties.put("spring.mustache.cache", "false");
		properties.put("server.servlet.session.persistent", "true");
		properties.put("spring.h2.console.enabled", "true");
		properties.put("spring.web.resources.cache.period", "0");
		properties.put("spring.web.resources.chain.cache", "false");
		properties.put("spring.template.provider.cache", "false");
		properties.put("spring.mvc.log-resolved-exception", "true");
		properties.put("server.error.include-binding-errors", "ALWAYS");
		properties.put("server.error.include-message", "ALWAYS");
		properties.put("server.error.include-stacktrace", "ALWAYS");
		properties.put("server.servlet.jsp.init-parameters.development", "true");
		properties.put("spring.reactor.debug", "true");
		PROPERTIES = Collections.unmodifiableMap(properties);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		// 开启devtools并且是本地程序
		if (DevToolsEnablementDeducer.shouldEnable(Thread.currentThread()) && isLocalApplication(environment)) {
			// 判断是否需要添加数据
			if (canAddProperties(environment)) {
				logger.info(LogMessage.format("Devtools property defaults active! Set '%s' to 'false' to disable",
						ENABLED));
				Map<String, Object> properties = new HashMap<>(PROPERTIES);
				properties.putAll(getResourceProperties(environment));
				environment.getPropertySources().addLast(new MapPropertySource("devtools", properties));
			}
			if (isWebApplication(environment) && !environment.containsProperty(WEB_LOGGING)) {
				logger.info(LogMessage.format(
						"For additional web related logging consider setting the '%s' property to 'DEBUG'",
						WEB_LOGGING));
			}
		}
	}

	private Map<String, String> getResourceProperties(Environment environment) {
		Map<String, String> resourceProperties = new HashMap<>();
		String prefix = determineResourcePropertiesPrefix(environment);
		resourceProperties.put(prefix + "cache.period", "0");
		resourceProperties.put(prefix + "chain.cache", "false");
		return resourceProperties;
	}

	@SuppressWarnings("deprecation")
	private String determineResourcePropertiesPrefix(Environment environment) {
		if (ClassUtils.isPresent("org.springframework.boot.autoconfigure.web.ResourceProperties",
				getClass().getClassLoader())) {
			BindResult<org.springframework.boot.autoconfigure.web.ResourceProperties> result = Binder.get(environment)
					.bind("spring.resources", org.springframework.boot.autoconfigure.web.ResourceProperties.class);
			if (result.isBound() && result.get().hasBeenCustomized()) {
				return "spring.resources.";
			}
		}
		return "spring.web.resources.";
	}

	private boolean isLocalApplication(ConfigurableEnvironment environment) {
		return environment.getPropertySources().get("remoteUrl") == null;
	}

	private boolean canAddProperties(Environment environment) {
		// 环境对象中spring.devtools.add-properties的值为true
		if (environment.getProperty(ENABLED, Boolean.class, true)) {
			// Restarter中初始化url不为空或者环境对象中spring.devtools.remote.secret数据存在
			return isRestarterInitialized() || isRemoteRestartEnabled(environment);
		}
		return false;
	}

	private boolean isRestarterInitialized() {
		try {
			Restarter restarter = Restarter.getInstance();
			return (restarter != null && restarter.getInitialUrls() != null);
		}
		catch (Exception ex) {
			return false;
		}
	}

	private boolean isRemoteRestartEnabled(Environment environment) {
		return environment.containsProperty("spring.devtools.remote.secret");
	}

	private boolean isWebApplication(Environment environment) {
		for (String candidate : WEB_ENVIRONMENT_CLASSES) {
			Class<?> environmentClass = resolveClassName(candidate, environment.getClass().getClassLoader());
			if (environmentClass != null && environmentClass.isInstance(environment)) {
				return true;
			}
		}
		return false;
	}

	private Class<?> resolveClassName(String candidate, ClassLoader classLoader) {
		try {
			return ClassUtils.resolveClassName(candidate, classLoader);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
