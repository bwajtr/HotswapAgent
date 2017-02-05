package org.hotswap.agent.plugin.owb;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;

import org.hotswap.agent.annotation.Init;
import org.hotswap.agent.annotation.LoadEvent;
import org.hotswap.agent.annotation.OnClassLoadEvent;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.command.Scheduler;
import org.hotswap.agent.config.PluginConfiguration;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.NotFoundException;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.owb.command.ArchiveAgentRegistry;
import org.hotswap.agent.plugin.owb.command.BeanClassRefreshCommand;
import org.hotswap.agent.util.IOUtils;
import org.hotswap.agent.util.ReflectionHelper;
import org.hotswap.agent.util.classloader.ClassLoaderHelper;
import org.hotswap.agent.watch.WatchEventListener;
import org.hotswap.agent.watch.WatchFileEvent;
import org.hotswap.agent.watch.Watcher;

/**
 * OwbPlugin
 *
 * @author Vladimir Dvorak
 */
@Plugin(name = "Owb",
        description = "Open Web Beans framework(http://openwebbeans.apache.org/). Reload, reinject bean, redefine proxy class after bean class definition/redefinition.",
        testedVersions = {"1.7.0"},
        expectedVersions = {"All between 1.7.0-1.7.0"},
        supportClass = { BeanArchiveTransformer.class, CdiContextsTransformer.class, WebBeanContextTransformer.class })
public class OwbPlugin {

    private static AgentLogger LOGGER = AgentLogger.getLogger(OwbPlugin.class);

    /** True for UnitTests */
    static boolean isTestEnvironment = false;

    /**
     * If a class is modified in IDE, sequence of multiple events is generated -
     * class file DELETE, CREATE, MODIFY, than Hotswap transformer is invoked.
     * ClassPathBeanRefreshCommand tries to merge these events into single command.
     * Wait for this this timeout(milliseconds) after class file event before ClassPathBeanRefreshCommand
     */
    private static final int WAIT_ON_CREATE = 500;
    private static final int WAIT_ON_REDEFINE = 200;

    @Init
    Watcher watcher;

    @Init
    Scheduler scheduler;

    @Init
    ClassLoader appClassLoader;

    @Init
    PluginConfiguration pluginConfiguration;

    boolean inJbossAS = false;

    boolean initialized = false;

    private Map<Object, Object> registeredProxiedBeans = new WeakHashMap<Object, Object>();

    private BeanReloadStrategy beanReloadStrategy;

    public void init() {
        if (!initialized) {
            LOGGER.info("CDI/Owb plugin initialized.");
            initialized = true;
            beanReloadStrategy = setBeanReloadStrategy(pluginConfiguration.getProperty("owb.beanReloadStrategy"));
        }
    }

    private BeanReloadStrategy setBeanReloadStrategy(String property) {
        BeanReloadStrategy ret = BeanReloadStrategy.NEVER;
        if (property != null && !property.isEmpty()) {
            try {
                ret = BeanReloadStrategy.valueOf(property);
            } catch (Exception e) {
                LOGGER.error("Unknown property 'owb.beanReloadStrategy' value: {} ", property);
            }
        }
        return ret;
    }

    /**
     * Register BeanArchive's normalizedArchivePath to watcher. In case of new class the class file is not known
     * to JVM hence no hotswap is called and therefore it must be handled by watcher.
     *
     * @param archivePath the archive path
     */
    public synchronized void registerBeanArchivePath(final String archivePath) {
        URL resource = null;
        try {
            resource = resourceNameToURL(archivePath);
            URI uri = resource.toURI();
            if (!IOUtils.isDirectoryURL(uri.toURL())) {
                LOGGER.trace("Owb - unable to watch files on URL '{}' for changes (JAR file?)", archivePath);
                return;
            } else {
                LOGGER.info("Registering archive path {}", archivePath);

                watcher.addEventListener(appClassLoader, uri, new WatchEventListener() {
                    @Override
                    public void onEvent(WatchFileEvent event) {
                        if (event.isFile() && event.getURI().toString().endsWith(".class")) {
                            // check that the class is not loaded by the classloader yet (avoid duplicate reload)
                            String className;
                            try {
                                className = IOUtils.urlToClassName(event.getURI());
                            } catch (IOException e) {
                                LOGGER.trace("Watch event on resource '{}' skipped, probably Ok because of delete/create event sequence (compilation not finished yet).",
                                        e, event.getURI());
                                return;
                            }
                            if (!ClassLoaderHelper.isClassLoaded(appClassLoader, className) || isTestEnvironment) {
                                // refresh weld only for new classes
                                LOGGER.trace("register reload command: {} ", className);
                                if (isBeanArchiveRegistered(appClassLoader, archivePath)) {
                                    // TODO : Create proxy factory
                                    scheduler.scheduleCommand(new BeanClassRefreshCommand(appClassLoader, archivePath, event), WAIT_ON_CREATE);
                                }
                            }
                        }
                    }
                });
            }
            LOGGER.info("Registered  watch for path '{}' for changes.", resource);
        } catch (URISyntaxException e) {
            LOGGER.error("Unable to watch path '{}' for changes.", e, resource);
        } catch (Exception e) {
            LOGGER.warning("registerBeanArchivePath() exception : {}",  e.getMessage());
        }
    }

    private static boolean isBeanArchiveRegistered(ClassLoader classLoader, String archivePath) {
        if (archivePath != null) {
            try {
                return (boolean) ReflectionHelper.invoke(null, Class.forName(ArchiveAgentRegistry.class.getName(), true, classLoader), "contains", new Class[] {String.class}, archivePath);
            } catch (ClassNotFoundException e) {
                LOGGER.error("isBeanArchiveRegistered() exception {}.", e.getMessage());
            }
        }
        return false;
    }

    public void registerProxyFactory(Object proxyFactory, Object bean, ClassLoader classLoader, Class<?> proxiedBeanType) {
        synchronized(registeredProxiedBeans) {
            if (!registeredProxiedBeans.containsKey(bean)) {
                LOGGER.debug("ProxyFactory for {} registered.", proxiedBeanType.getName());
            }
            registeredProxiedBeans.put(bean, proxyFactory);
        }
    }

    /**
     * If bda archive is defined for given class than new BeanClassRefreshCommand is created
     *
     * @param classLoader
     * @param ctClass
     * @param original
     */
    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public void classReload(ClassLoader classLoader, CtClass ctClass, Class<?> original) {
        if (original != null && !isSyntheticCdiClass(ctClass.getName()) && !isInnerNonPublicStaticClass(ctClass)) {
            try {
                String archivePath = getArchivePath(classLoader, ctClass, original.getName());
                LOGGER.debug("Class {} redefined for archive {} ", original.getName(), archivePath);
                if (isBeanArchiveRegistered(classLoader, archivePath)) {
                    String oldSignatureForProxyCheck = OwbClassSignatureHelper.getSignatureForProxyClass(original);
                    String oldSignatureByStrategy = OwbClassSignatureHelper.getSignatureByStrategy(beanReloadStrategy, original);
                    scheduler.scheduleCommand(new BeanClassRefreshCommand(classLoader, archivePath, registeredProxiedBeans,
                            original.getName(), oldSignatureForProxyCheck, oldSignatureByStrategy, beanReloadStrategy), WAIT_ON_REDEFINE);
                }
            } catch (Exception e) {
                LOGGER.error("classReload() exception {}.", e, e.getMessage());
            }
        }
    }

    private String getArchivePath(ClassLoader classLoader, CtClass ctClass, String knownClassName) throws NotFoundException {
         try {
             return (String) ReflectionHelper.invoke(null, Class.forName(ArchiveAgentRegistry.class.getName(), true, classLoader),
                     "getArchiveByClassName", new Class[] {String.class}, knownClassName);
         } catch (ClassNotFoundException e) {
             LOGGER.error("getArchivePath() exception {}.", e.getMessage());
         }

        String classFilePath = ctClass.getURL().getPath();
        String className = ctClass.getName().replace(".", "/");
        // archive path ends with '/', therefore we set end position before the '/' (-1)
        String archivePath = classFilePath.substring(0, classFilePath.indexOf(className) - 1);
        return (new File(archivePath)).toPath().toString();
    }

    public URL resourceNameToURL(String resource) throws Exception {
        try {
            // Try to format as a URL?
            return new URL(resource);
        } catch (MalformedURLException e) {
            // try to locate a file
            if (resource.startsWith("./"))
                resource = resource.substring(2);
            File file = new File(resource).getCanonicalFile();
            return file.toURI().toURL();
        }
    }

    // Return true if class is CDI synthetic class.
    // Owb proxies contains $Proxy$ and $$$
    // DeltaSpike's proxies contains "$$"
    private boolean isSyntheticCdiClass(String className) {
        return className.contains("$Proxy$") || className.contains("$$");
    }

    // Non static inner class is not allowed to be bean class
    private boolean isInnerNonPublicStaticClass(CtClass ctClass) {
        try {
            CtClass declaringClass = ctClass.getDeclaringClass();
            if (declaringClass != null && (
                    (ctClass.getModifiers() & Modifier.STATIC) == 0 ||
                    (ctClass.getModifiers() & Modifier.PUBLIC) == 0)) {
                return true;
            }
        } catch (NotFoundException e) {
            // swallow exception
        }
        return false;
    }

}