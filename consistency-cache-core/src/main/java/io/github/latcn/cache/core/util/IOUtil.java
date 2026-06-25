/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.latcn.cache.core.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IOUtil {

	/**
	 * Close Closeable resources
	 * @param closeables the closeables
	 */
	public static void close(AutoCloseable... closeables) {
		if (closeables != null && closeables.length > 0) {
			for (AutoCloseable closeable : closeables) {
				close(closeable);
			}
		}
	}

	/**
	 * Close Closeable resource
	 * @param closeable the closeable
	 */
	public static void close(AutoCloseable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			}
			catch (Exception e) {
				log.warn("Failed to close resource", e);
			}
		}
	}

}
