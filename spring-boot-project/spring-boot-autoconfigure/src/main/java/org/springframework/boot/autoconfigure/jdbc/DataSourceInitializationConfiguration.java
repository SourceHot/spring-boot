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

package org.springframework.boot.autoconfigure.jdbc;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Configures DataSource initialization.
 *
 * @author Stephane Nicoll
 */
@Configuration(proxyBeanMethods = false)
@Import({ DataSourceInitializerInvoker.class, DataSourceInitializationConfiguration.Registrar.class })
class DataSourceInitializationConfiguration {

	/**
	 * {@link ImportBeanDefinitionRegistrar} to register the
	 * {@link DataSourceInitializerPostProcessor} without causing early bean instantiation
	 * issues.
	 */
	static class Registrar implements ImportBeanDefinitionRegistrar {

		private static final String BEAN_NAME = "dataSourceInitializerPostProcessor";

		@Override
		public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
				BeanDefinitionRegistry registry) {
			// 判断bean注册容器中是否存在dataSourceInitializerPostProcessorbean，如果不存在则作相关处理
			if (!registry.containsBeanDefinition(BEAN_NAME)) {
				// 创建DataSourceInitializerPostProcessor对象所对应的BeanDefinition
				AbstractBeanDefinition beanDefinition = BeanDefinitionBuilder
						.genericBeanDefinition(DataSourceInitializerPostProcessor.class,
								DataSourceInitializerPostProcessor::new)
						.getBeanDefinition();
				// 设置role
				beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
				// We don't need this one to be post processed otherwise it can cause a
				// cascade of bean instantiation that we would rather avoid.
				// 设置是否合成标记
				beanDefinition.setSynthetic(true);
				// 注册bean
				registry.registerBeanDefinition(BEAN_NAME, beanDefinition);
			}
		}

	}

}
