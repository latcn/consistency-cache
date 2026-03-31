package com.consist.cache.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;


public class ClassUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassUtil.class);
    private static final String CLASS_FROM_FILE = "file";
    private static final String CLASS_FROM_JAR = "jar";

    public static <T> T newInstance(Class<T> clz) {
        try {
            if (!(clz.isMemberClass() && !Modifier.isStatic(clz.getModifiers()))) {
                try {
                    Constructor constructor = clz.getConstructor();
                    constructor.setAccessible(true);
                    return (T)constructor.newInstance(new Object[]{});
                } catch (Exception e) {
                }
            }
            Constructor[] constructorList = clz.getDeclaredConstructors();
            if (constructorList==null || constructorList.length==0) {
                throw new RuntimeException("no constructor");
            }
            Constructor constructor = constructorList[0];
            for(int i=1; i< constructorList.length; i++) {
                if (constructor.getParameterCount() > constructorList[i].getParameterCount()) {
                    constructor = constructorList[i];
                }
            }
            constructor.setAccessible(true);
            return (T)constructor.newInstance(new Object[constructor.getParameterCount()]);
        } catch (Exception e) {
            LOGGER.error("ex", e);
            throw new RuntimeException("newInstance error");
        }
    }

    public static <T> T newInstance(Class<T> clz, Class[] parameterTypes, Object[] parameters) {
        if (parameterTypes==null || parameterTypes.length==0) {
            return newInstance(clz);
        }
        if (clz.isMemberClass() && !Modifier.isStatic(clz.getModifiers())) {
            Constructor constructor = getConstructor(clz, parameterTypes);
            try {
                Object[] objects = new Object[parameterTypes.length+1];
                System.arraycopy(parameters, 0, objects, 1, parameterTypes.length);
                return (T) constructor.newInstance(objects);
            } catch (Exception e) {
                throw new RuntimeException("newInstance error");
            }
        } else {
            try {
                Constructor constructor = clz.getConstructor(parameterTypes);
                if (constructor==null) {
                    throw new RuntimeException("can't find constructor");
                }
                return (T)constructor.newInstance(parameters);
            } catch (Exception e) {
                LOGGER.error("ex", e);
                throw new RuntimeException(e.getMessage());
            }
        }
    }

    private static <T> Constructor getConstructor(Class<T> clz, Class[] parameterTypes) {
        Constructor[] constructors = clz.getDeclaredConstructors();
        Constructor constructor = null;
        for(int i=0; i<constructors.length; i++) {
            if (parameterTypes.length+1 == constructors[i].getParameterCount()) {
                boolean allMatch = true;
                for(Class parameterType: parameterTypes) {
                    if (parameterType!=constructors[i].getParameterTypes()[i+1]) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    constructor = constructors[i];
                    break;
                }
            }
        }
        if (constructor==null) {
            throw new RuntimeException("can't find constructor");
        }
        return constructor;
    }


    /**
     * 从配置文件中加载对应的类
     * @param resourcePath
     * @return
     */
    public static List<String[]> getClzAliasNameByResource(String resourcePath) {
        List<String[]> list = new ArrayList<>();
        try {
            Enumeration<URL> urls = ClassLoader.getSystemResources(resourcePath);
            if (urls==null || !urls.hasMoreElements()) {
                return list;
            }
            while(urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try(BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))){
                    String line;
                    while((line=bufferedReader.readLine())!=null) {
                        String[] clzAliasName = parseLineToClassName(line);
                        if (clzAliasName!=null) {
                            list.add(clzAliasName);
                        }
                    }
                } catch (Exception e){
                    LOGGER.error("load class error, url:{}", url);
                }
            }
        } catch (IOException e) {
            LOGGER.error("loadClassByResource", e);
        }
        return list;
    }

    private static String[] parseLineToClassName(String readLine) {
        try {
            if (StringUtil.isNullOrEmpty(readLine)) {
                return null;
            }
            readLine = readLine.trim();
            String[] splits = readLine.split("=");
            String alias = splits[0];
            String className = splits[1];
            return new String[]{alias, className};
        } catch (Exception e) {
            LOGGER.error("parseLineToClassName", e);
            return null;
        }
    }

    public static <T> List<Class<? extends T>> getAllClassByInterface(Class interfaceClass) {
        if (interfaceClass==null || !interfaceClass.isInterface()) {
            return new ArrayList<>();
        }
        List<Class<? extends T>> classList = new ArrayList<>();
        List<String> classNames = getAllClassNameByPackageName(interfaceClass.getPackageName());
        long t1 = System.currentTimeMillis();
        for(String className: classNames) {
            try {
                Class clz = Class.forName(className);
                if (interfaceClass.isAssignableFrom(clz)
                        && !clz.isInterface() && !isAbstractClass(clz) ){
                    classList.add(clz);
                }
            } catch (Exception e) {
                LOGGER.error("getAllClassByPackageName", e);
            }
        }
        LOGGER.info("getAllClassByPackageName costime: {}", System.currentTimeMillis()-t1);
        return classList;
    }

    public static List<String> getAllClassNameByPackageName(String packageName) {
        List<String> classNames = new ArrayList<>();
        if (StringUtil.isNullOrEmpty(packageName)) {
            return classNames;
        }
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        String packagePath = packageName.replace(".", "/");
        URL url = classLoader.getResource(packagePath);
        if (url==null) {
            return classNames;
        }
        String protocol = url.getProtocol();
        if (CLASS_FROM_FILE.equals(protocol)) {
            String fileDirPath = url.getPath().substring(0, url.getPath().indexOf("/classes")+9);
            classNames = getClassNameByFile(fileDirPath);
        }else if (CLASS_FROM_JAR.equals(protocol)) {
            try {
                JarFile jarFile = ((JarURLConnection) url.openConnection()).getJarFile();
                classNames = getClassNameByJar(jarFile, packagePath);
            } catch (IOException e) {
                LOGGER.error("getAllClassNameByPackageName jar: {}", packagePath);
            }
        }
        return classNames;
    }


    public static List<String> getClassNameByFile(String fileDirName) {
        LOGGER.debug("getClassNameByFile fileDir:{}", fileDirName);
        List<String> classNames = new ArrayList<>();
        File dirFile = new File(fileDirName);
        File[] files = dirFile.listFiles();
        for(File file: files) {
            if (file.isDirectory()) {
                classNames.addAll(getClassNameByFile(file.getPath()));
            } else {
                String filePath = file.getPath();
                LOGGER.debug("origin filePath: {}", filePath);
                if(!filePath.endsWith(".class")){
                    continue;
                }
                filePath = filePath.substring(filePath.indexOf("\\classes")+9, filePath.lastIndexOf("."));
                filePath = filePath.replace("\\", ".");
                classNames.add(filePath);
                LOGGER.debug("covert filePath: {}", filePath);
            }
        }
        return classNames;
    }

    public static List<String> getClassNameByJar(JarFile jarFile, String packagePath) {
        List<String> classNames = new ArrayList<>();
        try {
            Enumeration<JarEntry> entries = jarFile.entries();
            while(entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                String entryName = jarEntry.getName();
                if (entryName.endsWith(".class")) {
                    entryName = entryName.replace("/", ".").substring(0, entryName.lastIndexOf("."));
                    classNames.add(entryName);
                }
            }
        } catch (Exception e) {
            LOGGER.error("getClassNameByJar ex", e);
        }
        return classNames;
    }

    public static boolean isAbstractClass(Class clz) {
        return (clz.getModifiers() & Modifier.ABSTRACT) != 0;
    }

}
