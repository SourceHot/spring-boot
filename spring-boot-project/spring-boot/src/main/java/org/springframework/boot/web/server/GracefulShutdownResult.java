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

package org.springframework.boot.web.server;

/**
 * The result of a graceful shutdown request.
 *
 * @author Andy Wilkinson
 * @since 2.3.0
 * @see GracefulShutdownCallback
 * @see WebServer#shutDownGracefully(GracefulShutdownCallback)
 */
public enum GracefulShutdownResult {

	/**
	 * Requests remained active at the end of the grace period.
	 * 请求在宽限期结束时保持活动状态。假设宽限时间为10秒，在10秒后全部关闭
	 */
	REQUESTS_ACTIVE,

	/**
	 * The server was idle with no active requests at the end of the grace period.
	 * 在宽限期结束时，服务器处于空闲状态，没有活动请求。
	 */
	IDLE,

	/**
	 * The server was shutdown immediately, ignoring any active requests.
	 * 服务器立即关闭，忽略任何活动请求。
	 */
	IMMEDIATE;

}
