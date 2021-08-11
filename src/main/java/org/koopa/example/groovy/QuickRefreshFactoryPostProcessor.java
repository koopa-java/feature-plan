package org.koopa.example.groovy;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DelegatingIntroductionInterceptor;
import org.springframework.asm.Type;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.cglib.core.Signature;
import org.springframework.cglib.proxy.InterfaceMaker;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.Conventions;
import org.springframework.core.Ordered;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scripting.ScriptFactory;
import org.springframework.scripting.ScriptSource;
import org.springframework.scripting.support.RefreshableScriptTargetSource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.scripting.support.ScriptFactoryPostProcessor;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class QuickRefreshFactoryPostProcessor extends InstantiationAwareBeanPostProcessorAdapter implements
        BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware, DisposableBean, Ordered {

    /**
     * The {@link org.springframework.core.io.Resource}-style prefix that denotes
     * an inline script.
     * <p>An inline script is a script that is defined right there in the (typically XML)
     * configuration, as opposed to being defined in an external file.
     */
    public static final String INLINE_SCRIPT_PREFIX = "inline:";

    public static final String REFRESH_CHECK_DELAY_ATTRIBUTE = Conventions.getQualifiedAttributeName(
            ScriptFactoryPostProcessor.class, "refreshCheckDelay");

    public static final String PROXY_TARGET_CLASS_ATTRIBUTE = Conventions.getQualifiedAttributeName(
            ScriptFactoryPostProcessor.class, "proxyTargetClass");

    public static final String LANGUAGE_ATTRIBUTE = Conventions.getQualifiedAttributeName(
            ScriptFactoryPostProcessor.class, "language");

    private static final String SCRIPT_FACTORY_NAME_PREFIX = "scriptFactory.";

    private static final String SCRIPTED_OBJECT_NAME_PREFIX = "scriptedObject.";

    /**
     * Logger available to subclasses
     */
    protected final Log logger = LogFactory.getLog(getClass());

    private long defaultRefreshCheckDelay = -1;

    private boolean defaultProxyTargetClass = false;

    private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

    private ConfigurableBeanFactory beanFactory;

    private ResourceLoader resourceLoader = new DefaultResourceLoader();

    final DefaultListableBeanFactory scriptBeanFactory = new DefaultListableBeanFactory();

    /**
     * Map from bean name String to ScriptSource object
     */
    private final Map<String, ScriptSource> scriptSourceCache = new HashMap<String, ScriptSource>();

    /**
     * Set the delay between refresh checks, in milliseconds.
     * Default is -1, indicating no refresh checks at all.
     * <p>Note that an actual refresh will only happen when
     * the {@link ScriptSource} indicates
     * that it has been modified.
     *
     * @see ScriptSource#isModified()
     */
    public void setDefaultRefreshCheckDelay(long defaultRefreshCheckDelay) {
        this.defaultRefreshCheckDelay = defaultRefreshCheckDelay;
    }

    /**
     * Flag to signal that refreshable proxies should be created to proxy the target class not its interfaces.
     *
     * @param defaultProxyTargetClass the flag value to set
     */
    public void setDefaultProxyTargetClass(boolean defaultProxyTargetClass) {
        this.defaultProxyTargetClass = defaultProxyTargetClass;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        if (!(beanFactory instanceof ConfigurableBeanFactory)) {
            throw new IllegalStateException("ScriptFactoryPostProcessor doesn't work with a BeanFactory "
                    + "which does not implement ConfigurableBeanFactory: " + beanFactory.getClass());
        }
        this.beanFactory = (ConfigurableBeanFactory) beanFactory;

        // Required so that references (up container hierarchies) are correctly resolved.
        this.scriptBeanFactory.setParentBeanFactory(this.beanFactory);

        // Required so that all BeanPostProcessors, Scopes, etc become available.
        this.scriptBeanFactory.copyConfigurationFrom(this.beanFactory);

        // Filter out BeanPostProcessors that are part of the AOP infrastructure,
        // since those are only meant to apply to beans defined in the original factory.
        for (Iterator<BeanPostProcessor> it = this.scriptBeanFactory.getBeanPostProcessors().iterator(); it.hasNext(); ) {
            if (it.next() instanceof AopInfrastructureBean) {
                it.remove();
            }
        }
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public int getOrder() {
        // return Integer.MAX_VALUE;
        return Integer.MIN_VALUE;
    }

    @Override
    public Class predictBeanType(Class beanClass, String beanName) {
        // We only apply special treatment to ScriptFactory implementations here.
        if (!ScriptFactory.class.isAssignableFrom(beanClass)) {
            return null;
        }

        BeanDefinition bd = this.beanFactory.getMergedBeanDefinition(beanName);

        try {
            String scriptFactoryBeanName = SCRIPT_FACTORY_NAME_PREFIX + beanName;
            String scriptedObjectBeanName = SCRIPTED_OBJECT_NAME_PREFIX + beanName;
            prepareScriptBeans(bd, scriptFactoryBeanName, scriptedObjectBeanName);

            ScriptFactory scriptFactory = this.scriptBeanFactory.getBean(scriptFactoryBeanName, ScriptFactory.class);
            ScriptSource scriptSource = getScriptSource(scriptFactoryBeanName, scriptFactory.getScriptSourceLocator());
            Class[] interfaces = scriptFactory.getScriptInterfaces();

            Class scriptedType = scriptFactory.getScriptedObjectType(scriptSource);
            if (scriptedType != null) {
                return scriptedType;
            } else if (!ObjectUtils.isEmpty(interfaces)) {
                return (interfaces.length == 1 ? interfaces[0] : createCompositeInterface(interfaces));
            } else {
                if (bd.isSingleton()) {
                    Object bean = this.scriptBeanFactory.getBean(scriptedObjectBeanName);
                    if (bean != null) {
                        return bean.getClass();
                    }
                }
            }
        } catch (Exception ex) {
            if (ex instanceof BeanCreationException
                    && ((BeanCreationException) ex).getMostSpecificCause() instanceof BeanCurrentlyInCreationException) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Could not determine scripted object type for bean '" + beanName + "': "
                            + ex.getMessage());
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Could not determine scripted object type for bean '" + beanName + "'", ex);
                }
            }
        }

        return null;
    }

    @Override
    public Object postProcessBeforeInstantiation(Class beanClass, String beanName) {
        // We only apply special treatment to ScriptFactory implementations here.
        if (!ScriptFactory.class.isAssignableFrom(beanClass)) {
            return null;
        }

        BeanDefinition bd = this.beanFactory.getMergedBeanDefinition(beanName);
        String scriptFactoryBeanName = SCRIPT_FACTORY_NAME_PREFIX + beanName;
        String scriptedObjectBeanName = SCRIPTED_OBJECT_NAME_PREFIX + beanName;
        prepareScriptBeans(bd, scriptFactoryBeanName, scriptedObjectBeanName);

        ScriptFactory scriptFactory = this.scriptBeanFactory.getBean(scriptFactoryBeanName, ScriptFactory.class);
        ScriptSource scriptSource = getScriptSource(scriptFactoryBeanName, scriptFactory.getScriptSourceLocator());
        boolean isFactoryBean = false;
        try {
            Class scriptedObjectType = scriptFactory.getScriptedObjectType(scriptSource);
            // Returned type may be null if the factory is unable to determine the type.
            if (scriptedObjectType != null) {
                isFactoryBean = FactoryBean.class.isAssignableFrom(scriptedObjectType);
            }
        } catch (Exception ex) {
            throw new BeanCreationException(beanName, "Could not determine scripted object type for " + scriptFactory,
                    ex);
        }

        long refreshCheckDelay = resolveRefreshCheckDelay(bd);
        if (refreshCheckDelay >= 0) {
            Class[] interfaces = scriptFactory.getScriptInterfaces();
            RefreshableScriptTargetSource ts = new RefreshableScriptTargetSource(this.scriptBeanFactory,
                    scriptedObjectBeanName, scriptFactory, scriptSource, isFactoryBean);
            boolean proxyTargetClass = resolveProxyTargetClass(bd);
            String language = (String) bd.getAttribute(LANGUAGE_ATTRIBUTE);
            if (proxyTargetClass && (language == null || !language.equals("groovy"))) {
                throw new BeanDefinitionValidationException(
                        "Cannot use proxyTargetClass=true with script beans where language is not groovy (found "
                                + language + ")");
            }
            ts.setRefreshCheckDelay(refreshCheckDelay);
            return createRefreshableProxy(ts, interfaces, proxyTargetClass);
        }

        if (isFactoryBean) {
            scriptedObjectBeanName = BeanFactory.FACTORY_BEAN_PREFIX + scriptedObjectBeanName;
        }
        return this.scriptBeanFactory.getBean(scriptedObjectBeanName);
    }

    /**
     * Prepare the script beans in the internal BeanFactory that this
     * post-processor uses. Each original bean definition will be split
     * into a ScriptFactory definition and a scripted object definition.
     *
     * @param bd                     the original bean definition in the main BeanFactory
     * @param scriptFactoryBeanName  the name of the internal ScriptFactory bean
     * @param scriptedObjectBeanName the name of the internal scripted object bean
     */
    protected void prepareScriptBeans(BeanDefinition bd, String scriptFactoryBeanName, String scriptedObjectBeanName) {

        // Avoid recreation of the script bean definition in case of a prototype.
        synchronized (this.scriptBeanFactory) {
            if (!this.scriptBeanFactory.containsBeanDefinition(scriptedObjectBeanName)) {

                this.scriptBeanFactory.registerBeanDefinition(scriptFactoryBeanName,
                        createScriptFactoryBeanDefinition(bd));
                ScriptFactory scriptFactory = this.scriptBeanFactory
                        .getBean(scriptFactoryBeanName, ScriptFactory.class);
                ScriptSource scriptSource = getScriptSource(scriptFactoryBeanName,
                        scriptFactory.getScriptSourceLocator());
                Class<?>[] interfaces = scriptFactory.getScriptInterfaces();

                Class<?>[] scriptedInterfaces = interfaces;
                if (scriptFactory.requiresConfigInterface() && !bd.getPropertyValues().isEmpty()) {
                    Class<?> configInterface = createConfigInterface(bd, interfaces);
                    scriptedInterfaces = ObjectUtils.addObjectToArray(interfaces, configInterface);
                }

                BeanDefinition objectBd = createScriptedObjectBeanDefinition(bd, scriptFactoryBeanName, scriptSource,
                        scriptedInterfaces);
                long refreshCheckDelay = resolveRefreshCheckDelay(bd);
                if (refreshCheckDelay >= 0) {
                    objectBd.setScope(BeanDefinition.SCOPE_PROTOTYPE);
                }

                this.scriptBeanFactory.registerBeanDefinition(scriptedObjectBeanName, objectBd);
            }
        }
    }

    /**
     * Get the refresh check delay for the given {@link ScriptFactory} {@link BeanDefinition}.
     * If the {@link BeanDefinition} has a
     * {@link org.springframework.core.AttributeAccessor metadata attribute}
     * under the key {@link #REFRESH_CHECK_DELAY_ATTRIBUTE} which is a valid {@link Number}
     * type, then this value is used. Otherwise, the the {@link #defaultRefreshCheckDelay}
     * value is used.
     *
     * @param beanDefinition the BeanDefinition to check
     * @return the refresh check delay
     */
    protected long resolveRefreshCheckDelay(BeanDefinition beanDefinition) {
        long refreshCheckDelay = this.defaultRefreshCheckDelay;
        Object attributeValue = beanDefinition.getAttribute(REFRESH_CHECK_DELAY_ATTRIBUTE);
        if (attributeValue instanceof Number) {
            refreshCheckDelay = ((Number) attributeValue).longValue();
        } else if (attributeValue instanceof String) {
            refreshCheckDelay = Long.parseLong((String) attributeValue);
        } else if (attributeValue != null) {
            throw new BeanDefinitionStoreException("Invalid refresh check delay attribute ["
                    + REFRESH_CHECK_DELAY_ATTRIBUTE + "] with value [" + attributeValue
                    + "]: needs to be of type Number or String");
        }
        return refreshCheckDelay;
    }

    protected boolean resolveProxyTargetClass(BeanDefinition beanDefinition) {
        boolean proxyTargetClass = this.defaultProxyTargetClass;
        Object attributeValue = beanDefinition.getAttribute(PROXY_TARGET_CLASS_ATTRIBUTE);
        if (attributeValue instanceof Boolean) {
            proxyTargetClass = ((Boolean) attributeValue).booleanValue();
        } else if (attributeValue instanceof String) {
            proxyTargetClass = new Boolean((String) attributeValue);
        } else if (attributeValue != null) {
            throw new BeanDefinitionStoreException("Invalid refresh check delay attribute ["
                    + REFRESH_CHECK_DELAY_ATTRIBUTE + "] with value [" + attributeValue
                    + "]: needs to be of type Number or String");
        }
        return proxyTargetClass;
    }

    /**
     * Create a ScriptFactory bean definition based on the given script definition,
     * extracting only the definition data that is relevant for the ScriptFactory
     * (that is, only bean class and constructor arguments).
     *
     * @param bd the full script bean definition
     * @return the extracted ScriptFactory bean definition
     * @see ScriptFactory
     */
    protected BeanDefinition createScriptFactoryBeanDefinition(BeanDefinition bd) {
        GenericBeanDefinition scriptBd = new GenericBeanDefinition();
        scriptBd.setBeanClassName(bd.getBeanClassName());
        scriptBd.getConstructorArgumentValues().addArgumentValues(bd.getConstructorArgumentValues());
        return scriptBd;
    }

    /**
     * Obtain a ScriptSource for the given bean, lazily creating it
     * if not cached already.
     *
     * @param beanName            the name of the scripted bean
     * @param scriptSourceLocator the script source locator associated with the bean
     * @return the corresponding ScriptSource instance
     * @see #convertToScriptSource
     */
    protected ScriptSource getScriptSource(String beanName, String scriptSourceLocator) {
        synchronized (this.scriptSourceCache) {
            ScriptSource scriptSource = this.scriptSourceCache.get(beanName);
            if (scriptSource == null) {
                scriptSource = convertToScriptSource(beanName, scriptSourceLocator, this.resourceLoader);
                this.scriptSourceCache.put(beanName, scriptSource);
            }
            return scriptSource;
        }
    }

    /**
     * 获取脚本名称
     *
     * @param scriptSourceLocator
     * @return
     */
    private String getScriptName(String scriptSourceLocator) {
        String scriptName = this.substringAfter(scriptSourceLocator,
                GroovyConstant.SCRIPT_SOURCE_PREFIX);
        return this.toLowerCase(scriptName);
    }

    /**
     * Convert the given script source locator to a ScriptSource instance.
     * <p>By default, supported locators are Spring resource locations
     * (such as "file:C:/myScript.bsh" or "classpath:myPackage/myScript.bsh")
     * and inline scripts ("inline:myScriptText...").
     *
     * @param beanName            the name of the scripted bean
     * @param scriptSourceLocator the script source locator
     * @param resourceLoader      the ResourceLoader to use (if necessary)
     * @return the ScriptSource instance
     */
    protected ScriptSource convertToScriptSource(String beanName, String scriptSourceLocator,
                                                 ResourceLoader resourceLoader) {

        if (scriptSourceLocator.startsWith(INLINE_SCRIPT_PREFIX)) {
            return new StaticScriptSource(scriptSourceLocator.substring(INLINE_SCRIPT_PREFIX
                    .length()), beanName);
        } else if (scriptSourceLocator.startsWith(GroovyConstant.SCRIPT_SOURCE_PREFIX)) {
            String scriptName = getScriptName(scriptSourceLocator);
            return new DatabaseScriptSource(scriptName);
        } else {
            return new ResourceScriptSource(resourceLoader.getResource(scriptSourceLocator));
        }
    }

    /**
     * Create a config interface for the given bean definition, defining setter
     * methods for the defined property values as well as an init method and
     * a destroy method (if defined).
     * <p>This implementation creates the interface via CGLIB's InterfaceMaker,
     * determining the property types from the given interfaces (as far as possible).
     *
     * @param bd         the bean definition (property values etc) to create a
     *                   config interface for
     * @param interfaces the interfaces to check against (might define
     *                   getters corresponding to the setters we're supposed to generate)
     * @return the config interface
     * @see InterfaceMaker
     * @see BeanUtils#findPropertyType
     */
    protected Class createConfigInterface(BeanDefinition bd, Class[] interfaces) {
        InterfaceMaker maker = new InterfaceMaker();
        PropertyValue[] pvs = bd.getPropertyValues().getPropertyValues();
        for (PropertyValue pv : pvs) {
            String propertyName = pv.getName();
            Class propertyType = BeanUtils.findPropertyType(propertyName, interfaces);
            String setterName = "set" + StringUtils.capitalize(propertyName);
            Signature signature = new Signature(setterName, Type.VOID_TYPE, new Type[]{Type.getType(propertyType)});
            maker.add(signature, new Type[0]);
        }
        if (bd instanceof AbstractBeanDefinition) {
            AbstractBeanDefinition abd = (AbstractBeanDefinition) bd;
            if (abd.getInitMethodName() != null) {
                Signature signature = new Signature(abd.getInitMethodName(), Type.VOID_TYPE, new Type[0]);
                maker.add(signature, new Type[0]);
            }
            if (abd.getDestroyMethodName() != null) {
                Signature signature = new Signature(abd.getDestroyMethodName(), Type.VOID_TYPE, new Type[0]);
                maker.add(signature, new Type[0]);
            }
        }
        return maker.create();
    }

    /**
     * Create a composite interface Class for the given interfaces,
     * implementing the given interfaces in one single Class.
     * <p>The default implementation builds a JDK proxy class
     * for the given interfaces.
     *
     * @param interfaces the interfaces to merge
     * @return the merged interface as Class
     * @see java.lang.reflect.Proxy#getProxyClass
     */
    protected Class createCompositeInterface(Class[] interfaces) {
        return ClassUtils.createCompositeInterface(interfaces, this.beanClassLoader);
    }

    /**
     * Create a bean definition for the scripted object, based on the given script
     * definition, extracting the definition data that is relevant for the scripted
     * object (that is, everything but bean class and constructor arguments).
     *
     * @param bd                    the full script bean definition
     * @param scriptFactoryBeanName the name of the internal ScriptFactory bean
     * @param scriptSource          the ScriptSource for the scripted bean
     * @param interfaces            the interfaces that the scripted bean is supposed to implement
     * @return the extracted ScriptFactory bean definition
     * @see ScriptFactory#getScriptedObject
     */
    protected BeanDefinition createScriptedObjectBeanDefinition(BeanDefinition bd, String scriptFactoryBeanName,
                                                                ScriptSource scriptSource, Class[] interfaces) {

        GenericBeanDefinition objectBd = new GenericBeanDefinition(bd);
        objectBd.setFactoryBeanName(scriptFactoryBeanName);
        objectBd.setFactoryMethodName("getScriptedObject");
        objectBd.getConstructorArgumentValues().clear();
        objectBd.getConstructorArgumentValues().addIndexedArgumentValue(0, scriptSource);
        objectBd.getConstructorArgumentValues().addIndexedArgumentValue(1, interfaces);
        return objectBd;
    }

    /**
     * Create a refreshable proxy for the given AOP TargetSource.
     *
     * @param ts         the refreshable TargetSource
     * @param interfaces the proxy interfaces (may be {@code null} to
     *                   indicate proxying of all interfaces implemented by the target class)
     * @return the generated proxy
     * @see RefreshableScriptTargetSource
     */
    protected Object createRefreshableProxy(TargetSource ts, Class[] interfaces, boolean proxyTargetClass) {
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTargetSource(ts);
        ClassLoader classLoader = this.beanClassLoader;

        if (interfaces == null) {
            interfaces = ClassUtils.getAllInterfacesForClass(ts.getTargetClass(), this.beanClassLoader);
        }
        proxyFactory.setInterfaces(interfaces);
        if (proxyTargetClass) {
            classLoader = null; // Force use of Class.getClassLoader()
            proxyFactory.setProxyTargetClass(proxyTargetClass);
        }

        DelegatingIntroductionInterceptor introduction = new DelegatingIntroductionInterceptor(ts);
        introduction.suppressInterface(TargetSource.class);
        proxyFactory.addAdvice(introduction);

        return proxyFactory.getProxy(classLoader);
    }

    /**
     * Destroy the inner bean factory (used for scripts) on shutdown.
     */
    @Override
    public void destroy() {
        this.scriptBeanFactory.destroySingletons();
    }

    public void destorySingleton(String singletonName) {
        String scriptFactoryBeanName = SCRIPT_FACTORY_NAME_PREFIX + singletonName;
        String scriptedObjectBeanName = SCRIPTED_OBJECT_NAME_PREFIX + singletonName;
        this.scriptBeanFactory.removeBeanDefinition(scriptFactoryBeanName);
        this.scriptBeanFactory.removeBeanDefinition(scriptedObjectBeanName);
    }


    /**
     * 取得第一个出现的分隔子串之后的子串。
     * <p>
     * <p>
     * 如果字符串为<code>null</code>，则返回<code>null</code>。 如果分隔子串为<code>null</code> 或未找到该子串，则返回原字符串。
     * <p>
     * <pre>
     * StringUtil.substringAfter(null, *)      = null
     * StringUtil.substringAfter("", *)        = ""
     * StringUtil.substringAfter(*, null)      = ""
     * StringUtil.substringAfter("abc", "a")   = "bc"
     * StringUtil.substringAfter("abcba", "b") = "cba"
     * StringUtil.substringAfter("abc", "c")   = ""
     * StringUtil.substringAfter("abc", "d")   = ""
     * StringUtil.substringAfter("abc", "")    = "abc"
     * </pre>
     * <p>
     * </p>
     *
     * @param str       字符串
     * @param separator 要搜索的分隔子串
     * @return 子串，如果原始串为<code>null</code>，则返回<code>null</code>
     */
    private String substringAfter(String str, String separator) {
        if ((str == null) || (str.length() == 0)) {
            return str;
        }

        if (separator == null) {
            return StringUtils.EMPTY;
        }

        int pos = str.indexOf(separator);

        if (pos == -1) {
            return StringUtils.EMPTY;
        }

        return str.substring(pos + separator.length());
    }

    private String toLowerCase(String str) {
        if (str == null) {
            return null;
        }

        return str.toLowerCase();
    }
}
