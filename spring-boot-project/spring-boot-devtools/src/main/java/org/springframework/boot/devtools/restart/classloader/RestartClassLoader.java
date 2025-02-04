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

package org.springframework.boot.devtools.restart.classloader;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Enumeration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.devtools.restart.classloader.ClassLoaderFile.Kind;
import org.springframework.core.SmartClassLoader;
import org.springframework.util.Assert;

/**
 * Disposable {@link ClassLoader} used to support application restarting. Provides parent
 * last loading for the specified URLs.
 *
 * @author Andy Clement
 * @author Phillip Webb
 * @since 1.3.0
 */
public class RestartClassLoader extends URLClassLoader implements SmartClassLoader {

	private final Log logger;

	private final ClassLoaderFileRepository updatedFiles;

	/**
	 * Create a new {@link RestartClassLoader} instance.
	 * @param parent the parent classloader
	 * @param urls the urls managed by the classloader
	 */
	public RestartClassLoader(ClassLoader parent, URL[] urls) {
		this(parent, urls, ClassLoaderFileRepository.NONE);
	}

	/**
	 * Create a new {@link RestartClassLoader} instance.
	 * @param parent the parent classloader
	 * @param updatedFiles any files that have been updated since the JARs referenced in
	 * URLs were created.
	 * @param urls the urls managed by the classloader
	 */
	public RestartClassLoader(ClassLoader parent, URL[] urls, ClassLoaderFileRepository updatedFiles) {
		this(parent, urls, updatedFiles, LogFactory.getLog(RestartClassLoader.class));
	}

	/**
	 * Create a new {@link RestartClassLoader} instance.
	 * @param parent the parent classloader
	 * @param updatedFiles any files that have been updated since the JARs referenced in
	 * URLs were created.
	 * @param urls the urls managed by the classloader
	 * @param logger the logger used for messages
	 */
	public RestartClassLoader(ClassLoader parent, URL[] urls, ClassLoaderFileRepository updatedFiles, Log logger) {
		super(urls, parent);
		Assert.notNull(parent, "Parent must not be null");
		Assert.notNull(updatedFiles, "UpdatedFiles must not be null");
		Assert.notNull(logger, "Logger must not be null");
		this.updatedFiles = updatedFiles;
		this.logger = logger;
		if (logger.isDebugEnabled()) {
			logger.debug("Created RestartClassLoader " + toString());
		}
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		// Use the parent since we're shadowing resource and we don't want duplicates
		// 调用父类进行资源集合获取
		Enumeration<URL> resources = getParent().getResources(name);
		// 通过成员变量updatedFiles根据名称获取ClassLoaderFile对象
		ClassLoaderFile file = this.updatedFiles.getFile(name);
		// ClassLoaderFile对象不为空的情况下
		if (file != null) {
			// Assume that we're replacing just the first item
			if (resources.hasMoreElements()) {
				resources.nextElement();
			}
			// 获取类加载文件的种类如果不是删除则进行对象组装
			if (file.getKind() != Kind.DELETED) {
				return new CompoundEnumeration<>(createFileUrl(name, file), resources);
			}
		}
		return resources;
	}

	@Override
	public URL getResource(String name) {
		ClassLoaderFile file = this.updatedFiles.getFile(name);
		if (file != null && file.getKind() == Kind.DELETED) {
			return null;
		}
		URL resource = findResource(name);
		if (resource != null) {
			return resource;
		}
		return getParent().getResource(name);
	}

	@Override
	public URL findResource(String name) {
		final ClassLoaderFile file = this.updatedFiles.getFile(name);
		if (file == null) {
			return super.findResource(name);
		}
		if (file.getKind() == Kind.DELETED) {
			return null;
		}
		return AccessController.doPrivileged((PrivilegedAction<URL>) () -> createFileUrl(name, file));
	}

	@Override
	public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		// 将方法参数name做路径替换得到路径表示
		String path = name.replace('.', '/').concat(".class");
		// 通过成员变量updatedFiles配合path获取ClassLoaderFile对象
		ClassLoaderFile file = this.updatedFiles.getFile(path);
		// 如果ClassLoaderFile对象不为空并且ClassLoaderFile对象的kind属性为删除则抛出异常
		if (file != null && file.getKind() == Kind.DELETED) {
			throw new ClassNotFoundException(name);
		}
		synchronized (getClassLoadingLock(name)) {
			// 获取name对应的类对象
			Class<?> loadedClass = findLoadedClass(name);
			// 如果为空
			if (loadedClass == null) {
				try {
					// 进一步寻找类
					loadedClass = findClass(name);
				} catch (ClassNotFoundException ex) {
					// 当前类（RestartClassLoader）找不到类的情况下通过Class获取
					loadedClass = Class.forName(name, false, getParent());
				}
			}
			// 是否需要解析,如果需要则进行解析
			if (resolve) {
				resolveClass(loadedClass);
			}
			return loadedClass;
		}
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		// 将方法参数name做路径替换得到路径表示
		String path = name.replace('.', '/').concat(".class");
		// 通过成员变量updatedFiles获取ClassLoaderFile对象
		final ClassLoaderFile file = this.updatedFiles.getFile(path);
		// 若ClassLoaderFile对象为空则调用父类进行搜索类对象
		if (file == null) {
			return super.findClass(name);
		}
		// 如果ClassLoaderFile对象的kind属性为删除则抛出异常
		if (file.getKind() == Kind.DELETED) {
			throw new ClassNotFoundException(name);
		}
		// 返回类对象
		return AccessController.doPrivileged((PrivilegedAction<Class<?>>) () -> {
			byte[] bytes = file.getContents();
			return defineClass(name, bytes, 0, bytes.length);
		});
	}

	@Override
	public Class<?> publicDefineClass(String name, byte[] b, ProtectionDomain protectionDomain) {
		return defineClass(name, b, 0, b.length, protectionDomain);
	}

	@Override
	public ClassLoader getOriginalClassLoader() {
		return getParent();
	}

	private URL createFileUrl(String name, ClassLoaderFile file) {
		try {
			return new URL("reloaded", null, -1, "/" + name, new ClassLoaderFileURLStreamHandler(file));
		}
		catch (MalformedURLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	protected void finalize() throws Throwable {
		if (this.logger.isDebugEnabled()) {
			this.logger.debug("Finalized classloader " + toString());
		}
		super.finalize();
	}

	@Override
	public boolean isClassReloadable(Class<?> classType) {
		return (classType.getClassLoader() instanceof RestartClassLoader);
	}

	/**
	 * Compound {@link Enumeration} that adds an additional item to the front.
	 */
	private static class CompoundEnumeration<E> implements Enumeration<E> {

		private E firstElement;

		private final Enumeration<E> enumeration;

		CompoundEnumeration(E firstElement, Enumeration<E> enumeration) {
			this.firstElement = firstElement;
			this.enumeration = enumeration;
		}

		@Override
		public boolean hasMoreElements() {
			return (this.firstElement != null || this.enumeration.hasMoreElements());
		}

		@Override
		public E nextElement() {
			if (this.firstElement == null) {
				return this.enumeration.nextElement();
			}
			E element = this.firstElement;
			this.firstElement = null;
			return element;
		}

	}

}
