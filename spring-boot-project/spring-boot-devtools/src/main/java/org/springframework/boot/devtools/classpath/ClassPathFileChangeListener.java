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

package org.springframework.boot.devtools.classpath;

import java.util.Set;

import org.springframework.boot.devtools.filewatch.ChangedFile;
import org.springframework.boot.devtools.filewatch.ChangedFiles;
import org.springframework.boot.devtools.filewatch.FileChangeListener;
import org.springframework.boot.devtools.filewatch.FileSystemWatcher;
import org.springframework.boot.devtools.restart.AgentReloader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.Assert;

/**
 * A {@link FileChangeListener} to publish {@link ClassPathChangedEvent
 * ClassPathChangedEvents}.
 *
 * @author Phillip Webb
 * @see ClassPathFileSystemWatcher
 */
class ClassPathFileChangeListener implements FileChangeListener {
	/**
	 * 事件推送器
	 */
	private final ApplicationEventPublisher eventPublisher;
	/**
	 * 重启策略
	 */
	private final ClassPathRestartStrategy restartStrategy;
	/**
	 * 文件系统监控
	 */
	private final FileSystemWatcher fileSystemWatcherToStop;

	/**
	 * Create a new {@link ClassPathFileChangeListener} instance.
	 * @param eventPublisher the event publisher used send events
	 * @param restartStrategy the restart strategy to use
	 * @param fileSystemWatcherToStop the file system watcher to stop on a restart (or
	 * {@code null})
	 */
	ClassPathFileChangeListener(ApplicationEventPublisher eventPublisher, ClassPathRestartStrategy restartStrategy,
			FileSystemWatcher fileSystemWatcherToStop) {
		Assert.notNull(eventPublisher, "EventPublisher must not be null");
		Assert.notNull(restartStrategy, "RestartStrategy must not be null");
		this.eventPublisher = eventPublisher;
		this.restartStrategy = restartStrategy;
		this.fileSystemWatcherToStop = fileSystemWatcherToStop;
	}

	@Override
	public void onChange(Set<ChangedFiles> changeSet) {
		// 判断是否重启
		boolean restart = isRestartRequired(changeSet);
		// 推送事件
		publishEvent(new ClassPathChangedEvent(this, changeSet, restart));
	}

	private void publishEvent(ClassPathChangedEvent event) {
		this.eventPublisher.publishEvent(event);
		if (event.isRestartRequired() && this.fileSystemWatcherToStop != null) {
			this.fileSystemWatcherToStop.stop();
		}
	}

	private boolean isRestartRequired(Set<ChangedFiles> changeSet) {
		// 确定是否有任何代理重新加载器处于活动状态
		if (AgentReloader.isActive()) {
			return false;
		}
		// 循环处理方法参数changeSet
		for (ChangedFiles changedFiles : changeSet) {
			for (ChangedFile changedFile : changedFiles) {
				// 确认是否需要重启
				if (this.restartStrategy.isRestartRequired(changedFile)) {
					return true;
				}
			}
		}
		return false;
	}

}
