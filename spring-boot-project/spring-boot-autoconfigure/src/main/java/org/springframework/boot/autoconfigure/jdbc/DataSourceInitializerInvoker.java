/*
 * Copyright 2012-2019 the original author or authors.
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

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.log.LogMessage;

/**
 * Bean to handle {@link DataSource} initialization by running {@literal schema-*.sql} on
 * {@link InitializingBean#afterPropertiesSet()} and {@literal data-*.sql} SQL scripts on
 * a {@link DataSourceSchemaCreatedEvent}.
 *
 * @author Stephane Nicoll
 * @see DataSourceAutoConfiguration
 */
class DataSourceInitializerInvoker implements ApplicationListener<DataSourceSchemaCreatedEvent>, InitializingBean {

	private static final Log logger = LogFactory.getLog(DataSourceInitializerInvoker.class);
	/**
	 * 数据源
	 */
	private final ObjectProvider<DataSource> dataSource;
	/**
	 * 数据源属性表
	 */
	private final DataSourceProperties properties;
	/**
	 * 应用上下文
	 */
	private final ApplicationContext applicationContext;
	/**
	 * 数据源实例化程序
	 */
	private DataSourceInitializer dataSourceInitializer;
	/**
	 * 是否实例化
	 */
	private boolean initialized;

	DataSourceInitializerInvoker(ObjectProvider<DataSource> dataSource, DataSourceProperties properties,
								 ApplicationContext applicationContext) {
		this.dataSource = dataSource;
		this.properties = properties;
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterPropertiesSet() {
		// 获取数据源实例化程序
		DataSourceInitializer initializer = getDataSourceInitializer();
		// 数据源实例化程序不为空的情况下进行初始化
		if (initializer != null) {
			// 创建schema
			boolean schemaCreated = this.dataSourceInitializer.createSchema();
			if (schemaCreated) {
				// 实例化数据源
				initialize(initializer);
			}
		}
	}

	private void initialize(DataSourceInitializer initializer) {
		try {
			// 推送DataSourceSchemaCreatedEvent事件
			this.applicationContext.publishEvent(new DataSourceSchemaCreatedEvent(initializer.getDataSource()));
			// The listener might not be registered yet, so don't rely on it.
			// 处理data相关的sql初始化
			if (!this.initialized) {
				this.dataSourceInitializer.initSchema();
				this.initialized = true;
			}
		} catch (IllegalStateException ex) {
			logger.warn(LogMessage.format("Could not send event to complete DataSource initialization (%s)",
					ex.getMessage()));
		}
	}

	@Override
	public void onApplicationEvent(DataSourceSchemaCreatedEvent event) {
		// NOTE the event can happen more than once and
		// the event datasource is not used here
		DataSourceInitializer initializer = getDataSourceInitializer();
		if (!this.initialized && initializer != null) {
			initializer.initSchema();
			this.initialized = true;
		}
	}

	private DataSourceInitializer getDataSourceInitializer() {
		if (this.dataSourceInitializer == null) {
			DataSource ds = this.dataSource.getIfUnique();
			if (ds != null) {
				this.dataSourceInitializer = new DataSourceInitializer(ds, this.properties, this.applicationContext);
			}
		}
		return this.dataSourceInitializer;
	}

}
