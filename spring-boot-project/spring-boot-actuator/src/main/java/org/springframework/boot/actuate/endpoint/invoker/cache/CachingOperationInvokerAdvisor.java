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

package org.springframework.boot.actuate.endpoint.invoker.cache;

import java.util.function.Function;

import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.http.ApiVersion;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvoker;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.OperationParameter;
import org.springframework.boot.actuate.endpoint.invoke.OperationParameters;

/**
 * {@link OperationInvokerAdvisor} to optionally provide result caching support.
 *
 * @author Stephane Nicoll
 * @since 2.0.0
 */
public class CachingOperationInvokerAdvisor implements OperationInvokerAdvisor {

	private final Function<EndpointId, Long> endpointIdTimeToLive;

	public CachingOperationInvokerAdvisor(Function<EndpointId, Long> endpointIdTimeToLive) {
		this.endpointIdTimeToLive = endpointIdTimeToLive;
	}

	@Override
	public OperationInvoker apply(EndpointId endpointId, OperationType operationType, OperationParameters parameters,
			OperationInvoker invoker) {
		// 操作类型是READ并且没有强制参数
		if (operationType == OperationType.READ && !hasMandatoryParameter(parameters)) {
			// 获取
			Long timeToLive = this.endpointIdTimeToLive.apply(endpointId);
			if (timeToLive != null && timeToLive > 0) {
				return new CachingOperationInvoker(invoker, timeToLive);
			}
		}
		return invoker;
	}

	/**
	 * 判断是否存在强制参数
	 */
	private boolean hasMandatoryParameter(OperationParameters parameters) {
		for (OperationParameter parameter : parameters) {
			// 1. 参数必须
			// 2. 参数类型不是ApiVersion和SecurityContext
			if (parameter.isMandatory() && !ApiVersion.class.isAssignableFrom(parameter.getType())
					&& !SecurityContext.class.isAssignableFrom(parameter.getType())) {
				return true;
			}
		}
		return false;
	}

}
