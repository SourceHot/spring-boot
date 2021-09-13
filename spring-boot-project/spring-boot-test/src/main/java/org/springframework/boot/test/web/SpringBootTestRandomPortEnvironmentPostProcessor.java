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

package org.springframework.boot.test.web;

import java.util.Objects;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.util.ClassUtils;

/**
 * {@link EnvironmentPostProcessor} implementation to start the management context on a
 * random port if the main server's port is 0 and the management context is expected on a
 * different port.
 *
 * @author Madhura Bhave
 * @author Andy Wilkinson
 */
class SpringBootTestRandomPortEnvironmentPostProcessor implements EnvironmentPostProcessor {

	private static final String MANAGEMENT_PORT_PROPERTY = "management.server.port";

	private static final String SERVER_PORT_PROPERTY = "server.port";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		// 从环境配置中获取TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME对应的属性源
		MapPropertySource source = (MapPropertySource) environment.getPropertySources()
				.get(TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME);
		// 如果属性源为空或者服务端口不为0或者management.server.port数据不为空则跳过处理
		if (source == null || isTestServerPortFixed(source, environment) || isTestManagementPortConfigured(source)) {
			return;
		}
		// 获取management.server.port数据
		Integer managementPort = getPropertyAsInteger(environment, MANAGEMENT_PORT_PROPERTY, null);
		// 如果management.server.port数据不存在或者数值为-1或者数值为0跳过处理
		if (managementPort == null || managementPort.equals(-1) || managementPort.equals(0)) {
			return;
		}
		// 获取server.port数据，默认值8080
		Integer serverPort = getPropertyAsInteger(environment, SERVER_PORT_PROPERTY, 8080);
		// 如果management.server.port数据和server.port数据不相等则向数据源设置management.server.port数据为0，相等则设置空字符串
		if (!managementPort.equals(serverPort)) {
			source.getSource().put(MANAGEMENT_PORT_PROPERTY, "0");
		} else {
			source.getSource().put(MANAGEMENT_PORT_PROPERTY, "");
		}
	}

	private boolean isTestServerPortFixed(MapPropertySource source, ConfigurableEnvironment environment) {
		return !Integer.valueOf(0).equals(getPropertyAsInteger(source, SERVER_PORT_PROPERTY, environment));
	}

	private boolean isTestManagementPortConfigured(PropertySource<?> source) {
		return source.getProperty(MANAGEMENT_PORT_PROPERTY) != null;
	}

	private Integer getPropertyAsInteger(ConfigurableEnvironment environment, String property, Integer defaultValue) {
		return environment.getPropertySources().stream().filter(
				(source) -> !source.getName().equals(TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME))
				.map((source) -> getPropertyAsInteger(source, property, environment)).filter(Objects::nonNull)
				.findFirst().orElse(defaultValue);
	}

	private Integer getPropertyAsInteger(PropertySource<?> source, String property,
			ConfigurableEnvironment environment) {
		Object value = source.getProperty(property);
		if (value == null) {
			return null;
		}
		if (ClassUtils.isAssignableValue(Integer.class, value)) {
			return (Integer) value;
		}
		try {
			return environment.getConversionService().convert(value, Integer.class);
		}
		catch (ConversionFailedException ex) {
			if (value instanceof String) {
				return getResolvedValueIfPossible(environment, (String) value);
			}
			throw ex;
		}
	}

	private Integer getResolvedValueIfPossible(ConfigurableEnvironment environment, String value) {
		String resolvedValue = environment.resolveRequiredPlaceholders(value);
		return environment.getConversionService().convert(resolvedValue, Integer.class);
	}

}
