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

package org.springframework.boot.test.mock.mockito;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.mockito.Mockito;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.ClassUtils;

/**
 * {@link TestExecutionListener} to reset any mock beans that have been marked with a
 * {@link MockReset}. Typically used alongside {@link MockitoTestExecutionListener}.
 *
 * @author Phillip Webb
 * @since 1.4.0
 * @see MockitoTestExecutionListener
 */
public class ResetMocksTestExecutionListener extends AbstractTestExecutionListener {

	private static final boolean MOCKITO_IS_PRESENT = ClassUtils.isPresent("org.mockito.MockSettings",
			ResetMocksTestExecutionListener.class.getClassLoader());

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 100;
	}

	@Override
	public void beforeTestMethod(TestContext testContext) throws Exception {
		if (MOCKITO_IS_PRESENT) {
			resetMocks(testContext.getApplicationContext(), MockReset.BEFORE);
		}
	}

	@Override
	public void afterTestMethod(TestContext testContext) throws Exception {
		if (MOCKITO_IS_PRESENT) {
			resetMocks(testContext.getApplicationContext(), MockReset.AFTER);
		}
	}

	private void resetMocks(ApplicationContext applicationContext, MockReset reset) {
		if (applicationContext instanceof ConfigurableApplicationContext) {
			resetMocks((ConfigurableApplicationContext) applicationContext, reset);
		}
	}

	private void resetMocks(ConfigurableApplicationContext applicationContext, MockReset reset) {
		// 获取bean工厂
		ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
		// 获取bean工厂中bean定义的名称集合
		String[] names = beanFactory.getBeanDefinitionNames();
		// 获取单例bean定义的名称集合
		Set<String> instantiatedSingletons = new HashSet<>(Arrays.asList(beanFactory.getSingletonNames()));
		// 循环处理bean名称集合
		for (String name : names) {
			// 获取bean定义
			BeanDefinition definition = beanFactory.getBeanDefinition(name);
			// bean定义中表示单例并且在单例bean名称集合中存在
			if (definition.isSingleton() && instantiatedSingletons.contains(name)) {
				// 获取bean实例
				Object bean = beanFactory.getSingleton(name);
				// 如果相同则重新设置
				if (reset.equals(MockReset.get(bean))) {
					Mockito.reset(bean);
				}
			}
		}
		try {
			// 从bean工厂中获取MockitoBeans对象
			MockitoBeans mockedBeans = beanFactory.getBean(MockitoBeans.class);
			// 循环MockitoBeans对象
			for (Object mockedBean : mockedBeans) {
				// 如果相同则进行重新设置
				if (reset.equals(MockReset.get(mockedBean))) {
					Mockito.reset(mockedBean);
				}
			}
		} catch (NoSuchBeanDefinitionException ex) {
			// Continue
		}
		// 父上下文不为空
		if (applicationContext.getParent() != null) {
			// 调用外部方法来进行当前方法的调用
			resetMocks(applicationContext.getParent(), reset);
		}
	}

}
