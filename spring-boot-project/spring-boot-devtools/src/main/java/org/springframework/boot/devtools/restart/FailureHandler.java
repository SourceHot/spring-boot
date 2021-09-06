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

package org.springframework.boot.devtools.restart;

/**
 * Strategy used to handle launch failures.
 *
 * 用于确认启动失败的策略。
 * @author Phillip Webb
 * @since 1.3.0
 */
@FunctionalInterface
public interface FailureHandler {

	/**
	 * {@link FailureHandler} that always aborts.
	 */
	FailureHandler NONE = (failure) -> Outcome.ABORT;

	/**
	 * Handle a run failure. Implementations may block, for example to wait until specific
	 * files are updated.
	 * @param failure the exception
	 * @return the outcome
	 */
	Outcome handle(Throwable failure);

	/**
	 * Various outcomes for the handler.
	 */
	enum Outcome {

		/**
		 * Abort the relaunch.
		 * 中止重新启动。
		 */
		ABORT,

		/**
		 * Try again to relaunch the application.
		 * 再次尝试重新启动应用程序。
		 */
		RETRY

	}

}
