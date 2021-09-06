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

package org.springframework.boot.devtools.remote.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.devtools.classpath.ClassPathChangedEvent;
import org.springframework.boot.devtools.filewatch.ChangedFile;
import org.springframework.boot.devtools.filewatch.ChangedFiles;
import org.springframework.boot.devtools.restart.classloader.ClassLoaderFile;
import org.springframework.boot.devtools.restart.classloader.ClassLoaderFile.Kind;
import org.springframework.boot.devtools.restart.classloader.ClassLoaderFiles;
import org.springframework.context.ApplicationListener;
import org.springframework.core.log.LogMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;

/**
 * Listens and pushes any classpath updates to a remote endpoint.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @since 1.3.0
 */
public class ClassPathChangeUploader implements ApplicationListener<ClassPathChangedEvent> {

	private static final Map<ChangedFile.Type, ClassLoaderFile.Kind> TYPE_MAPPINGS;

	static {
		Map<ChangedFile.Type, ClassLoaderFile.Kind> map = new EnumMap<>(ChangedFile.Type.class);
		map.put(ChangedFile.Type.ADD, ClassLoaderFile.Kind.ADDED);
		map.put(ChangedFile.Type.DELETE, ClassLoaderFile.Kind.DELETED);
		map.put(ChangedFile.Type.MODIFY, ClassLoaderFile.Kind.MODIFIED);
		TYPE_MAPPINGS = Collections.unmodifiableMap(map);
	}

	private static final Log logger = LogFactory.getLog(ClassPathChangeUploader.class);

	private final URI uri;

	private final ClientHttpRequestFactory requestFactory;

	public ClassPathChangeUploader(String url, ClientHttpRequestFactory requestFactory) {
		Assert.hasLength(url, "URL must not be empty");
		Assert.notNull(requestFactory, "RequestFactory must not be null");
		try {
			this.uri = new URL(url).toURI();
		}
		catch (URISyntaxException | MalformedURLException ex) {
			throw new IllegalArgumentException("Malformed URL '" + url + "'");
		}
		this.requestFactory = requestFactory;
	}

	@Override
	public void onApplicationEvent(ClassPathChangedEvent event) {
		try {
			// 获取类加载文件集合
			ClassLoaderFiles classLoaderFiles = getClassLoaderFiles(event);
			// 序列化
			byte[] bytes = serialize(classLoaderFiles);
			// 上传
			performUpload(classLoaderFiles, bytes);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void performUpload(ClassLoaderFiles classLoaderFiles, byte[] bytes) throws IOException {
		try {
			while (true) {
				try {
					// 创建http请求
					ClientHttpRequest request = this.requestFactory.createRequest(this.uri, HttpMethod.POST);
					// 获取请求头
					HttpHeaders headers = request.getHeaders();
					// 设置content-type
					headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
					// 设置content-length
					headers.setContentLength(bytes.length);
					// 值拷贝
					FileCopyUtils.copy(bytes, request.getBody());
					// http请求执行获取响应结果
					ClientHttpResponse response = request.execute();
					// 获取响应状态
					HttpStatus statusCode = response.getStatusCode();
					// 若响应状态非ok则抛出异常
					Assert.state(statusCode == HttpStatus.OK,
							() -> "Unexpected " + statusCode + " response uploading class files");
					// 写出日志
					logUpload(classLoaderFiles);
					return;
				}
				catch (SocketException ex) {
					logger.warn(LogMessage.format(
							"A failure occurred when uploading to %s. Upload will be retried in 2 seconds", this.uri));
					logger.debug("Upload failure", ex);
					Thread.sleep(2000);
				}
			}
		}
		catch (InterruptedException ex) {
			// 终止当前线程
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private void logUpload(ClassLoaderFiles classLoaderFiles) {
		int size = classLoaderFiles.size();
		logger.info(LogMessage.format("Uploaded %s class %s", size, (size != 1) ? "resources" : "resource"));
	}

	private byte[] serialize(ClassLoaderFiles classLoaderFiles) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
		objectOutputStream.writeObject(classLoaderFiles);
		objectOutputStream.close();
		return outputStream.toByteArray();
	}

	private ClassLoaderFiles getClassLoaderFiles(ClassPathChangedEvent event) throws IOException {
		// 创建ClassLoaderFiles对象
		ClassLoaderFiles files = new ClassLoaderFiles();
		// 从事件对象中提取变化的文件集合，遍历变化的文件集合
		for (ChangedFiles changedFiles : event.getChangeSet()) {
			// 获取目录对象的绝对路径
			String sourceDirectory = changedFiles.getSourceDirectory().getAbsolutePath();
			// 遍历文件集合对象
			for (ChangedFile changedFile : changedFiles) {
				// 向ClassLoaderFiles对象加入数据
				files.addFile(sourceDirectory, changedFile.getRelativeName(), asClassLoaderFile(changedFile));
			}
		}
		return files;
	}

	private ClassLoaderFile asClassLoaderFile(ChangedFile changedFile) throws IOException {
		ClassLoaderFile.Kind kind = TYPE_MAPPINGS.get(changedFile.getType());
		byte[] bytes = (kind != Kind.DELETED) ? FileCopyUtils.copyToByteArray(changedFile.getFile()) : null;
		long lastModified = (kind != Kind.DELETED) ? changedFile.getFile().lastModified() : System.currentTimeMillis();
		return new ClassLoaderFile(kind, lastModified, bytes);
	}

}
