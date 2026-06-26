package io.github.latcn.cache.spring.model;

import io.github.latcn.cache.core.exception.CacheError;
import io.github.latcn.cache.core.exception.CacheException;
import io.github.latcn.cache.core.util.StringUtil;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;

@Slf4j
public class RedisScriptCache {

	private final ConcurrentHashMap<String, ScriptInfo> scriptMap = new ConcurrentHashMap<>();

	private final RScript rScript;

	public RedisScriptCache(RScript rScript) {
		this.rScript = rScript;
	}

	public void registerScript(String scriptName, String script) {
		ScriptInfo scriptInfo = new ScriptInfo(scriptName, script);
		this.scriptMap.computeIfAbsent(scriptInfo.getScriptName(), (k) -> {
			scriptLoad(scriptInfo);
			return scriptInfo;
		});
	}

	public String reloadCachedSha1(String scriptName) {
		ScriptInfo scriptInfo = this.scriptMap.get(scriptName);
		if (scriptInfo == null) {
			throw new CacheException(CacheError.REDIS_SCRIPT_NOT_FOUND, scriptName);
		}
		scriptLoad(scriptInfo);
		return scriptInfo.getCachedSha1();
	}

	public String getScript(String scriptName) {
		ScriptInfo scriptInfo = this.scriptMap.get(scriptName);
		if (scriptInfo == null) {
			throw new CacheException(CacheError.REDIS_SCRIPT_NOT_FOUND, scriptName);
		}
		return scriptInfo.getScript();
	}

	public String getCachedSha1(String scriptName) {
		ScriptInfo scriptInfo = this.scriptMap.get(scriptName);
		if (scriptInfo == null) {
			return null;
		}
		String cachedSha1 = scriptInfo.getCachedSha1();
		if (StringUtil.isNullOrEmpty(cachedSha1)) {
			scriptLoad(scriptInfo);
		}
		return scriptInfo.getCachedSha1();
	}

	private void scriptLoad(ScriptInfo scriptInfo) {
		if (scriptInfo == null) {
			return;
		}
		synchronized (scriptInfo) {
			try {
				String cachedSha1 = this.rScript.scriptLoad(scriptInfo.getScript());
				scriptInfo.setCachedSha1(cachedSha1);
			}
			catch (Exception e) {
				log.error("scriptLoad ex", e);
			}
		}
	}

	class ScriptInfo {

		private final String scriptName;

		private final String script;

		private String cachedSha1;

		public ScriptInfo(String scriptName, String script) {
			if (StringUtil.isNullOrEmpty(scriptName) || StringUtil.isNullOrEmpty((script))) {
				throw new CacheException(CacheError.INVALID_PARAMETER, "redis script can't be null");
			}
			this.scriptName = scriptName;
			this.script = script;
		}

		public String getScriptName() {
			return scriptName;
		}

		public String getScript() {
			return script;
		}

		public String getCachedSha1() {
			return cachedSha1;
		}

		public void setCachedSha1(String cachedSha1) {
			this.cachedSha1 = cachedSha1;
		}

	}

}
