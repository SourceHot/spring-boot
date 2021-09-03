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

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.devtools.filewatch.ChangedFile.Type;
import org.springframework.util.Assert;

/**
 * A snapshot of a directory at a given point in time.
 *
 * @author Phillip Webb
 */
class DirectorySnapshot {

	private static final Set<String> DOTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(".", "..")));

	/**
	 * 目录
	 */
	private final File directory;

	/**
	 * 快照登记时间
	 */
	private final Date time;

	/**
	 * 文件快照集合
	 */
	private Set<FileSnapshot> files;

	/**
	 * Create a new {@link DirectorySnapshot} for the given directory.
	 * @param directory the source directory
	 */
	DirectorySnapshot(File directory) {
		Assert.notNull(directory, "Directory must not be null");
		Assert.isTrue(!directory.isFile(), () -> "Directory '" + directory + "' must not be a file");
		this.directory = directory;
		this.time = new Date();
		Set<FileSnapshot> files = new LinkedHashSet<>();
		collectFiles(directory, files);
		this.files = Collections.unmodifiableSet(files);
	}

	private void collectFiles(File source, Set<FileSnapshot> result) {
		File[] children = source.listFiles();
		if (children != null) {
			for (File child : children) {
				if (child.isDirectory() && !DOTS.contains(child.getName())) {
					collectFiles(child, result);
				}
				else if (child.isFile()) {
					result.add(new FileSnapshot(child));
				}
			}
		}
	}

	ChangedFiles getChangedFiles(DirectorySnapshot snapshot, FileFilter triggerFilter) {
		Assert.notNull(snapshot, "Snapshot must not be null");
		// 获取目录对象
		File directory = this.directory;
		Assert.isTrue(snapshot.directory.equals(directory),
				() -> "Snapshot source directory must be '" + directory + "'");
		// 创建变更文件集合
		Set<ChangedFile> changes = new LinkedHashSet<>();
		// 获取历史文件快照集合
		Map<File, FileSnapshot> previousFiles = getFilesMap();
		// 循环处理传入的文件集合
		for (FileSnapshot currentFile : snapshot.files) {
			// 经过过滤器处理确定是否需要处理
			if (acceptChangedFile(triggerFilter, currentFile)) {
				// 从历史文件快照中移除当前处理的文件快照对象，并获取该移除的对象
				FileSnapshot previousFile = previousFiles.remove(currentFile.getFile());
				// 历史移除对象为空的情况下加入到文件变更集合中
				if (previousFile == null) {
					changes.add(new ChangedFile(directory, currentFile.getFile(), Type.ADD));
				}
				// 历史文件快照对象和当前文件快照对象不相同加入到文件变更集合中
				else if (!previousFile.equals(currentFile)) {
					changes.add(new ChangedFile(directory, currentFile.getFile(), Type.MODIFY));
				}
			}
		}
		// 将历史文件快照集中的元素放入到变更文件集合
		for (FileSnapshot previousFile : previousFiles.values()) {
			if (acceptChangedFile(triggerFilter, previousFile)) {
				changes.add(new ChangedFile(directory, previousFile.getFile(), Type.DELETE));
			}
		}
		return new ChangedFiles(directory, changes);
	}

	private boolean acceptChangedFile(FileFilter triggerFilter, FileSnapshot file) {
		return (triggerFilter == null || !triggerFilter.accept(file.getFile()));
	}

	private Map<File, FileSnapshot> getFilesMap() {
		Map<File, FileSnapshot> files = new LinkedHashMap<>();
		for (FileSnapshot file : this.files) {
			files.put(file.getFile(), file);
		}
		return files;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (obj instanceof DirectorySnapshot) {
			return equals((DirectorySnapshot) obj, null);
		}
		return super.equals(obj);
	}


	boolean equals(DirectorySnapshot other, FileFilter filter) {
		// 对比当前目录对象和传入快照对象中的目录对象是否相同，如果不相同返回false
		if (this.directory.equals(other.directory)) {
			// 当前文件快照对象
			Set<FileSnapshot> ourFiles = filter(this.files, filter);
			// 传入的文件快照对象
			Set<FileSnapshot> otherFiles = filter(other.files, filter);
			// 文件快照对比
			return ourFiles.equals(otherFiles);
		}
		return false;
	}

	private Set<FileSnapshot> filter(Set<FileSnapshot> source, FileFilter filter) {
		// 文件过滤器为空的情况下直接返回
		if (filter == null) {
			return source;
		}
		// 创建结果集合
		Set<FileSnapshot> filtered = new LinkedHashSet<>();
		// 循环文件快照对象，通过文件过滤器判断是否是需要的文件，如果需要将其放入到结果集合中
		for (FileSnapshot file : source) {
			if (filter.accept(file.getFile())) {
				filtered.add(file);
			}
		}
		return filtered;
	}

	@Override
	public int hashCode() {
		int hashCode = this.directory.hashCode();
		hashCode = 31 * hashCode + this.files.hashCode();
		return hashCode;
	}

	/**
	 * Return the source directory of this snapshot.
	 * @return the source directory
	 */
	File getDirectory() {
		return this.directory;
	}

	@Override
	public String toString() {
		return this.directory + " snapshot at " + this.time;
	}

}
