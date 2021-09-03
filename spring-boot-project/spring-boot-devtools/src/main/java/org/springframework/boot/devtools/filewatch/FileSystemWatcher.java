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

package org.springframework.boot.devtools.filewatch;

import org.springframework.util.Assert;

import java.io.File;
import java.io.FileFilter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches specific directories for file changes.
 *
 * @author Andy Clement
 * @author Phillip Webb
 * @see FileChangeListener
 * @since 1.3.0
 */
public class FileSystemWatcher {

	/**
	 * 默认轮询间隔
	 */
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(1000);

	/**
	 * 默认无操作时间
	 */
	private static final Duration DEFAULT_QUIET_PERIOD = Duration.ofMillis(400);

	/**
	 * 文件变化监听器
	 */
	private final List<FileChangeListener> listeners = new ArrayList<>();

	/**
	 * 是否守护线程标记
	 */
	private final boolean daemon;
	/**
	 * 轮询间隔
	 */
	private final long pollInterval;
	/**
	 * 无操作时间
	 */
	private final long quietPeriod;
	/**
	 * 快照存储库
	 */
	private final SnapshotStateRepository snapshotStateRepository;
	/**
	 * 剩余扫描数量
	 */
	private final AtomicInteger remainingScans = new AtomicInteger(-1);
	/**
	 * 文件快照容器
	 */
	private final Map<File, DirectorySnapshot> directories = new HashMap<>();
	/**
	 * 锁
	 */
	private final Object monitor = new Object();
	/**
	 * 监控线程
	 */
	private Thread watchThread;
	/**
	 * 文件过滤器
	 */
	private FileFilter triggerFilter;

	/**
	 * Create a new {@link FileSystemWatcher} instance.
	 */
	public FileSystemWatcher() {
		this(true, DEFAULT_POLL_INTERVAL, DEFAULT_QUIET_PERIOD);
	}

	/**
	 * Create a new {@link FileSystemWatcher} instance.
	 *
	 * @param daemon       if a daemon thread used to monitor changes
	 * @param pollInterval the amount of time to wait between checking for changes
	 * @param quietPeriod  the amount of time required after a change has been detected to
	 *                     ensure that updates have completed
	 */
	public FileSystemWatcher(boolean daemon, Duration pollInterval, Duration quietPeriod) {
		this(daemon, pollInterval, quietPeriod, null);
	}

	/**
	 * Create a new {@link FileSystemWatcher} instance.
	 *
	 * @param daemon                  if a daemon thread used to monitor changes
	 * @param pollInterval            the amount of time to wait between checking for changes
	 * @param quietPeriod             the amount of time required after a change has been detected to
	 *                                ensure that updates have completed
	 * @param snapshotStateRepository the snapshot state repository
	 * @since 2.4.0
	 */
	public FileSystemWatcher(boolean daemon, Duration pollInterval, Duration quietPeriod,
							 SnapshotStateRepository snapshotStateRepository) {
		Assert.notNull(pollInterval, "PollInterval must not be null");
		Assert.notNull(quietPeriod, "QuietPeriod must not be null");
		Assert.isTrue(pollInterval.toMillis() > 0, "PollInterval must be positive");
		Assert.isTrue(quietPeriod.toMillis() > 0, "QuietPeriod must be positive");
		Assert.isTrue(pollInterval.toMillis() > quietPeriod.toMillis(),
				"PollInterval must be greater than QuietPeriod");
		this.daemon = daemon;
		this.pollInterval = pollInterval.toMillis();
		this.quietPeriod = quietPeriod.toMillis();
		this.snapshotStateRepository = (snapshotStateRepository != null) ? snapshotStateRepository
				: SnapshotStateRepository.NONE;
	}

	/**
	 * Add listener for file change events. Cannot be called after the watcher has been
	 * {@link #start() started}.
	 *
	 * @param fileChangeListener the listener to add
	 */
	public void addListener(FileChangeListener fileChangeListener) {
		Assert.notNull(fileChangeListener, "FileChangeListener must not be null");
		synchronized (this.monitor) {
			checkNotStarted();
			this.listeners.add(fileChangeListener);
		}
	}

	/**
	 * Add source directories to monitor. Cannot be called after the watcher has been
	 * {@link #start() started}.
	 *
	 * @param directories the directories to monitor
	 */
	public void addSourceDirectories(Iterable<File> directories) {
		Assert.notNull(directories, "Directories must not be null");
		synchronized (this.monitor) {
			directories.forEach(this::addSourceDirectory);
		}
	}

	/**
	 * Add a source directory to monitor. Cannot be called after the watcher has been
	 * {@link #start() started}.
	 *
	 * @param directory the directory to monitor
	 */
	public void addSourceDirectory(File directory) {
		Assert.notNull(directory, "Directory must not be null");
		Assert.isTrue(!directory.isFile(), () -> "Directory '" + directory + "' must not be a file");
		synchronized (this.monitor) {
			checkNotStarted();
			this.directories.put(directory, null);
		}
	}

	/**
	 * Set an optional {@link FileFilter} used to limit the files that trigger a change.
	 *
	 * @param triggerFilter a trigger filter or null
	 */
	public void setTriggerFilter(FileFilter triggerFilter) {
		synchronized (this.monitor) {
			this.triggerFilter = triggerFilter;
		}
	}

	private void checkNotStarted() {
		synchronized (this.monitor) {
			Assert.state(this.watchThread == null, "FileSystemWatcher already started");
		}
	}

	/**
	 * Start monitoring the source directory for changes.
	 */
	public void start() {
		synchronized (this.monitor) {
			// 创建或恢复初始快照
			createOrRestoreInitialSnapshots();
			// 监控线程为空的情况下处理
			if (this.watchThread == null) {
				// 获取成员变量directories
				Map<File, DirectorySnapshot> localDirectories = new HashMap<>(this.directories);
				// 创建Watcher对象,该对象是Runnable接口的实现类
				Watcher watcher = new Watcher(this.remainingScans, new ArrayList<>(this.listeners), this.triggerFilter,
						this.pollInterval, this.quietPeriod, localDirectories, this.snapshotStateRepository);
				// 创建线程对象,并启动线程对象
				this.watchThread = new Thread(watcher);
				this.watchThread.setName("File Watcher");
				this.watchThread.setDaemon(this.daemon);
				this.watchThread.start();
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void createOrRestoreInitialSnapshots() {
		Map<File, DirectorySnapshot> restored = (Map<File, DirectorySnapshot>) this.snapshotStateRepository.restore();
		this.directories.replaceAll((f, v) -> {
			DirectorySnapshot restoredSnapshot = (restored != null) ? restored.get(f) : null;
			return (restoredSnapshot != null) ? restoredSnapshot : new DirectorySnapshot(f);
		});
	}

	/**
	 * Stop monitoring the source directories.
	 */
	public void stop() {
		stopAfter(0);
	}

	/**
	 * Stop monitoring the source directories.
	 *
	 * @param remainingScans the number of remaining scans
	 */
	void stopAfter(int remainingScans) {
		Thread thread;
		synchronized (this.monitor) {
			// 监控线程
			thread = this.watchThread;
			// 监控线程不为空的情况下
			if (thread != null) {
				// 设置剩余扫描数量
				this.remainingScans.set(remainingScans);
				// 如果数量小于等于0，线程中断
				if (remainingScans <= 0) {
					thread.interrupt();
				}
			}
			// 设置监控线程为null
			this.watchThread = null;
		}
		// 线程对象不为空并且当前线程和线程对象不相同的情况下将线程join
		if (thread != null && Thread.currentThread() != thread) {
			try {
				thread.join();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static final class Watcher implements Runnable {

		/**
		 * 剩余扫描数量
		 */
		private final AtomicInteger remainingScans;

		/**
		 * 文件变更监听器
		 */
		private final List<FileChangeListener> listeners;

		/**
		 * 文件过滤器
		 */
		private final FileFilter triggerFilter;

		/**
		 * 轮询间隔
		 */
		private final long pollInterval;

		/**
		 * 无操作时间
		 */
		private final long quietPeriod;
		/**
		 * 快照存储库
		 */
		private final SnapshotStateRepository snapshotStateRepository;
		/**
		 * 文件快照容器
		 */
		private Map<File, DirectorySnapshot> directories;

		private Watcher(AtomicInteger remainingScans, List<FileChangeListener> listeners, FileFilter triggerFilter,
						long pollInterval, long quietPeriod, Map<File, DirectorySnapshot> directories,
						SnapshotStateRepository snapshotStateRepository) {
			this.remainingScans = remainingScans;
			this.listeners = listeners;
			this.triggerFilter = triggerFilter;
			this.pollInterval = pollInterval;
			this.quietPeriod = quietPeriod;
			this.directories = directories;
			this.snapshotStateRepository = snapshotStateRepository;

		}

		@Override
		public void run() {
			// 获取未扫描文件数量
			int remainingScans = this.remainingScans.get();
			while (remainingScans > 0 || remainingScans == -1) {
				try {
					if (remainingScans > 0) {
						// 数量-1
						this.remainingScans.decrementAndGet();
					}
					// 扫描
					scan();
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				// 修订当前未扫描文件数量
				remainingScans = this.remainingScans.get();
			}
		}

		private void scan() throws InterruptedException {
			Thread.sleep(this.pollInterval - this.quietPeriod);
			// 历史快照
			Map<File, DirectorySnapshot> previous;
			// 当前快照
			Map<File, DirectorySnapshot> current = this.directories;
			do {
				previous = current;
				// 获取当前快照
				current = getCurrentSnapshots();
				Thread.sleep(this.quietPeriod);
			}
			// 没有差异停止
			while (isDifferent(previous, current));
			// 如果存在差异则更新快照
			if (isDifferent(this.directories, current)) {
				updateSnapshots(current.values());
			}
		}

		/**
		 * 确认快照是否存在差异
		 */
		private boolean isDifferent(Map<File, DirectorySnapshot> previous, Map<File, DirectorySnapshot> current) {
			// 历史快照的key和当前快照的key不相同返回true
			if (!previous.keySet().equals(current.keySet())) {
				return true;
			}
			// 循环历史快照
			for (Map.Entry<File, DirectorySnapshot> entry : previous.entrySet()) {
				// 通过历史快照的key获取历史快照对象
				DirectorySnapshot previousDirectory = entry.getValue();
				// 通过历史快照的key获取当前快照对象
				DirectorySnapshot currentDirectory = current.get(entry.getKey());
				// 通过快照对比如果不相同返回true
				if (!previousDirectory.equals(currentDirectory, this.triggerFilter)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * 获取当前快照
		 */
		private Map<File, DirectorySnapshot> getCurrentSnapshots() {
			// 快照集合对象
			Map<File, DirectorySnapshot> snapshots = new LinkedHashMap<>();
			// 循环成员变量directories
			for (File directory : this.directories.keySet()) {
				// 向快照集合对象中设置数据
				snapshots.put(directory, new DirectorySnapshot(directory));
			}
			return snapshots;
		}

		/**
		 * 更新快照
		 */
		private void updateSnapshots(Collection<DirectorySnapshot> snapshots) {
			// 创建更新用的目录快照对象
			Map<File, DirectorySnapshot> updated = new LinkedHashMap<>();
			// 变化的文件集合
			Set<ChangedFiles> changeSet = new LinkedHashSet<>();
			// 遍历目录快照对象
			for (DirectorySnapshot snapshot : snapshots) {
				// 获取历史目录快照对象
				DirectorySnapshot previous = this.directories.get(snapshot.getDirectory());
				// 设置到更新目录快照对象中
				updated.put(snapshot.getDirectory(), snapshot);
				// 获取变化的文件集合
				ChangedFiles changedFiles = previous.getChangedFiles(snapshot, this.triggerFilter);
				// 变化的文件集合中文件对象不为空将其加入到变化的文件集合对象中
				if (!changedFiles.getFiles().isEmpty()) {
					changeSet.add(changedFiles);
				}
			}
			// 设置成员变量
			this.directories = updated;
			// 快照保存
			this.snapshotStateRepository.save(updated);
			// 变化的文件集合对象不为空，唤醒文件变更监听器
			if (!changeSet.isEmpty()) {
				fireListeners(Collections.unmodifiableSet(changeSet));
			}
		}

		private void fireListeners(Set<ChangedFiles> changeSet) {
			for (FileChangeListener listener : this.listeners) {
				listener.onChange(changeSet);
			}
		}

	}

}
