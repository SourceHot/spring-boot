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

package org.springframework.boot.test.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.web.SpringBootMockServletContext;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.reactive.context.GenericReactiveWebApplicationContext;
import org.springframework.boot.web.servlet.support.ServletContextApplicationContextInitializer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.SpringVersion;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextLoader;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.support.AbstractContextLoader;
import org.springframework.test.context.support.AnnotationConfigContextLoaderUtils;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.context.web.WebMergedContextConfiguration;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.GenericWebApplicationContext;

/**
 * A {@link ContextLoader} that can be used to test Spring Boot applications (those that
 * normally startup using {@link SpringApplication}). Although this loader can be used
 * directly, most test will instead want to use it with
 * {@link SpringBootTest @SpringBootTest}.
 * <p>
 * The loader supports both standard {@link MergedContextConfiguration} as well as
 * {@link WebMergedContextConfiguration}. If {@link WebMergedContextConfiguration} is used
 * the context will either use a mock servlet environment, or start the full embedded web
 * server.
 * <p>
 * If {@code @ActiveProfiles} are provided in the test class they will be used to create
 * the application context.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @author Scott Frederick
 * @since 1.4.0
 * @see SpringBootTest
 */
public class SpringBootContextLoader extends AbstractContextLoader {

	@Override
	public ApplicationContext loadContext(MergedContextConfiguration config) throws Exception {
		// 获取配置类集合
		Class<?>[] configClasses = config.getClasses();
		// 获取资源集合
		String[] configLocations = config.getLocations();
		// 数据检查
		Assert.state(!ObjectUtils.isEmpty(configClasses) || !ObjectUtils.isEmpty(configLocations),
				() -> "No configuration classes or locations found in @SpringApplicationConfiguration. "
						+ "For default configuration detection to work you need Spring 4.0.3 or better (found "
						+ SpringVersion.getVersion() + ").");
		// 获取SpringApplication对象
		SpringApplication application = getSpringApplication();
		// 设置主类
		application.setMainApplicationClass(config.getTestClass());
		// 设置属性
		application.addPrimarySources(Arrays.asList(configClasses));
		application.getSources().addAll(Arrays.asList(configLocations));
		// 获取环境配置
		ConfigurableEnvironment environment = getEnvironment();
		// 如果配置中存在profiles将设置到环境配置中
		if (!ObjectUtils.isEmpty(config.getActiveProfiles())) {
			setActiveProfiles(environment, config.getActiveProfiles());
		}
		// 获取资源加载器
		ResourceLoader resourceLoader = (application.getResourceLoader() != null) ? application.getResourceLoader()
				: new DefaultResourceLoader(null);
		// 添加资源到环境配置中
		TestPropertySourceUtils.addPropertiesFilesToEnvironment(environment, resourceLoader,
				config.getPropertySourceLocations());
		TestPropertySourceUtils.addInlinedPropertiesToEnvironment(environment, getInlinedProperties(config));
		// 为应用上下文设置环境配置
		application.setEnvironment(environment);
		// 获取ApplicationContextInitializer接口实现类集合
		List<ApplicationContextInitializer<?>> initializers = getInitializers(config, application);
		// 如果配置是WebMergedContextConfiguration类型
		if (config instanceof WebMergedContextConfiguration) {
			// 设置web应用类型为servlet
			application.setWebApplicationType(WebApplicationType.SERVLET);
			// 确认是否开启web环境，如果没有则创建WebConfigurer对象并进行配置
			if (!isEmbeddedWebEnvironment(config)) {
				new WebConfigurer().configure(config, application, initializers);
			}
		}
		// 如果配置是ReactiveWebMergedContextConfiguration类型
		else if (config instanceof ReactiveWebMergedContextConfiguration) {
			// 设置web应用类型为reactive
			application.setWebApplicationType(WebApplicationType.REACTIVE);
			// 确认是否开启web环境，如果没有则设置GenericReactiveWebApplicationContext对象到上下文工厂中
			if (!isEmbeddedWebEnvironment(config)) {
				application.setApplicationContextFactory(
						ApplicationContextFactory.of(GenericReactiveWebApplicationContext::new));
			}
		}
		// 其他情况将设置web应用类型为none
		else {
			application.setWebApplicationType(WebApplicationType.NONE);
		}
		// 为应用上下文设置ApplicationContextInitializer接口实现类集合
		application.setInitializers(initializers);
		// 获取启动参数
		String[] args = SpringBootTestArgs.get(config.getContextCustomizers());
		// 执行run方法获取应用上下文
		return application.run(args);
	}

	/**
	 * Builds new {@link org.springframework.boot.SpringApplication} instance. You can
	 * override this method to add custom behavior
	 * @return {@link org.springframework.boot.SpringApplication} instance
	 */
	protected SpringApplication getSpringApplication() {
		return new SpringApplication();
	}

	/**
	 * Builds a new {@link ConfigurableEnvironment} instance. You can override this method
	 * to return something other than {@link StandardEnvironment} if necessary.
	 * @return a {@link ConfigurableEnvironment} instance
	 */
	protected ConfigurableEnvironment getEnvironment() {
		return new StandardEnvironment();
	}

	private void setActiveProfiles(ConfigurableEnvironment environment, String[] profiles) {
		environment.setActiveProfiles(profiles);
		// Also add as properties to override any application.properties
		String[] pairs = new String[profiles.length];
		for (int i = 0; i < profiles.length; i++) {
			pairs[i] = "spring.profiles.active[" + i + "]=" + profiles[i];
		}
		TestPropertyValues.of(pairs).applyTo(environment);
	}

	protected String[] getInlinedProperties(MergedContextConfiguration config) {
		ArrayList<String> properties = new ArrayList<>();
		// JMX bean names will clash if the same bean is used in multiple contexts
		disableJmx(properties);
		properties.addAll(Arrays.asList(config.getPropertySourceProperties()));
		return StringUtils.toStringArray(properties);
	}

	private void disableJmx(List<String> properties) {
		properties.add("spring.jmx.enabled=false");
	}

	/**
	 * Return the {@link ApplicationContextInitializer initializers} that will be applied
	 * to the context. By default this method will adapt {@link ContextCustomizer context
	 * customizers}, add {@link SpringApplication#getInitializers() application
	 * initializers} and add
	 * {@link MergedContextConfiguration#getContextInitializerClasses() initializers
	 * specified on the test}.
	 * @param config the source context configuration
	 * @param application the application instance
	 * @return the initializers to apply
	 * @since 2.0.0
	 */
	protected List<ApplicationContextInitializer<?>> getInitializers(MergedContextConfiguration config,
			SpringApplication application) {
		List<ApplicationContextInitializer<?>> initializers = new ArrayList<>();
		for (ContextCustomizer contextCustomizer : config.getContextCustomizers()) {
			initializers.add(new ContextCustomizerAdapter(contextCustomizer, config));
		}
		initializers.addAll(application.getInitializers());
		for (Class<? extends ApplicationContextInitializer<?>> initializerClass : config
				.getContextInitializerClasses()) {
			initializers.add(BeanUtils.instantiateClass(initializerClass));
		}
		if (config.getParent() != null) {
			initializers.add(new ParentContextApplicationContextInitializer(config.getParentApplicationContext()));
		}
		return initializers;
	}

	private boolean isEmbeddedWebEnvironment(MergedContextConfiguration config) {
		return MergedAnnotations.from(config.getTestClass(), SearchStrategy.TYPE_HIERARCHY).get(SpringBootTest.class)
				.getValue("webEnvironment", WebEnvironment.class).orElse(WebEnvironment.NONE).isEmbedded();
	}

	@Override
	public void processContextConfiguration(ContextConfigurationAttributes configAttributes) {
		super.processContextConfiguration(configAttributes);
		if (!configAttributes.hasResources()) {
			Class<?>[] defaultConfigClasses = detectDefaultConfigurationClasses(configAttributes.getDeclaringClass());
			configAttributes.setClasses(defaultConfigClasses);
		}
	}

	/**
	 * Detect the default configuration classes for the supplied test class. By default
	 * simply delegates to
	 * {@link AnnotationConfigContextLoaderUtils#detectDefaultConfigurationClasses}.
	 * @param declaringClass the test class that declared {@code @ContextConfiguration}
	 * @return an array of default configuration classes, potentially empty but never
	 * {@code null}
	 * @see AnnotationConfigContextLoaderUtils
	 */
	protected Class<?>[] detectDefaultConfigurationClasses(Class<?> declaringClass) {
		return AnnotationConfigContextLoaderUtils.detectDefaultConfigurationClasses(declaringClass);
	}

	@Override
	public ApplicationContext loadContext(String... locations) throws Exception {
		throw new UnsupportedOperationException(
				"SpringApplicationContextLoader does not support the loadContext(String...) method");
	}

	@Override
	protected String[] getResourceSuffixes() {
		return new String[] { "-context.xml", "Context.groovy" };
	}

	@Override
	protected String getResourceSuffix() {
		throw new IllegalStateException();
	}

	/**
	 * Inner class to configure {@link WebMergedContextConfiguration}.
	 */
	private static class WebConfigurer {

		void configure(MergedContextConfiguration configuration, SpringApplication application,
				List<ApplicationContextInitializer<?>> initializers) {
			WebMergedContextConfiguration webConfiguration = (WebMergedContextConfiguration) configuration;
			addMockServletContext(initializers, webConfiguration);
			application.setApplicationContextFactory((webApplicationType) -> new GenericWebApplicationContext());
		}

		private void addMockServletContext(List<ApplicationContextInitializer<?>> initializers,
				WebMergedContextConfiguration webConfiguration) {
			SpringBootMockServletContext servletContext = new SpringBootMockServletContext(
					webConfiguration.getResourceBasePath());
			initializers.add(0, new ServletContextApplicationContextInitializer(servletContext, true));
		}

	}

	/**
	 * Adapts a {@link ContextCustomizer} to a {@link ApplicationContextInitializer} so
	 * that it can be triggered via {@link SpringApplication}.
	 * 上下文适配器
	 */
	private static class ContextCustomizerAdapter
			implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		private final ContextCustomizer contextCustomizer;

		private final MergedContextConfiguration config;

		ContextCustomizerAdapter(ContextCustomizer contextCustomizer, MergedContextConfiguration config) {
			this.contextCustomizer = contextCustomizer;
			this.config = config;
		}

		@Override
		public void initialize(ConfigurableApplicationContext applicationContext) {
			this.contextCustomizer.customizeContext(applicationContext, this.config);
		}

	}

	/**
	 * 父上下文初始化器
	 */
	@Order(Ordered.HIGHEST_PRECEDENCE)
	private static class ParentContextApplicationContextInitializer
			implements ApplicationContextInitializer<ConfigurableApplicationContext> {

		private final ApplicationContext parent;

		ParentContextApplicationContextInitializer(ApplicationContext parent) {
			this.parent = parent;
		}

		@Override
		public void initialize(ConfigurableApplicationContext applicationContext) {
			applicationContext.setParent(this.parent);
		}

	}

}
