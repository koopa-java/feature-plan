package org.koopa.example.groovy;

import groovy.lang.GroovyClassLoader;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.ResourceEntityResolver;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StopWatch;

import java.util.Arrays;
import java.util.List;

@Component("groovyDynamicLoader")
public class GroovyDynamicLoader implements ApplicationContextAware, InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(
			GroovyDynamicLoader.class);
    private static final String QUICK_REFRESH_FACTORY_POST_PROCESSOR = "quickRefreshFactoryPostProcessor";

    /**
     * Spring 上下文环境
     */
    private ConfigurableApplicationContext applicationContext;

    /**
     * 脚本ClassLoader
     */
    private static final GroovyClassLoader groovyClassLoader = new GroovyClassLoader(
            GroovyDynamicLoader.class.getClassLoader());


    public void addBeanTemplates(List<GroovyBeanTemplate> templates) {

        //将脚本模板写入
        final ConfigurationXMLWriter configurationXMLWriter = new ConfigurationXMLWriter();
        templates.forEach(e -> writeBean(e, configurationXMLWriter));

        try {
            //加载
            this.load(configurationXMLWriter);

        } catch (RuntimeException e) {
            logger.error("脚本启动加载异常", e);
        }

    }

    public void removeBeanTemplates(List<GroovyBeanTemplate> templates) {
        templates.forEach(this::removeBeanTemplate);
    }

    public void addBeanTemplate(GroovyBeanTemplate template) {
        addBeanTemplates(Arrays.asList(template));
    }

    public void removeBeanTemplate(GroovyBeanTemplate template) {
        QuickRefreshFactoryPostProcessor processor = (QuickRefreshFactoryPostProcessor) applicationContext
                .getBean(QUICK_REFRESH_FACTORY_POST_PROCESSOR);
        try {
            String className = toLowerCase(template.getClassName());
            processor.destorySingleton(className);
            logger.info("Destroy groovy bean {}.", className);
        } catch (NoSuchBeanDefinitionException e) {
            logger.info("Bean to destroyed is not exist", e);
        }
    }

    private String toLowerCase(String str) {
        if (str == null) {
            return null;
        }

        return str.toLowerCase();
    }

    private boolean writeBean(GroovyBeanTemplate template, ConfigurationXMLWriter configurationXMLWriter) {
        //通过编译器检查脚本语法是否正确，编译失败则不放入springContext
        if (!checkSyntax(template)) {
            return false;
        }

        DynamicBean bean = new DynamicBean();
        String scriptName = template.getClassName();

        Assert.notNull(scriptName);

        //设置bean的属性，这里只有id和script-source。
        bean.put("id", scriptName);
        bean.put("script-source", GroovyConstant.SCRIPT_SOURCE_PREFIX + scriptName);

        configurationXMLWriter.write(GroovyConstant.SPRING_TAG, bean);
        return true;
    }

    /**
     * 将包括所有脚本bean的xml配置文件，在当前spring context中加载
     */
    private void load(ConfigurationXMLWriter configurationXMLWriter) {
        String contextString = configurationXMLWriter.getContent();
        if (StringUtils.isEmpty(contextString)) {
            logger.warn("脚本内容为空，跳过加载");
            return;
        }


        XmlBeanDefinitionReader beanDefinitionReader = new XmlBeanDefinitionReader(
                (BeanDefinitionRegistry) this.applicationContext.getBeanFactory());
        beanDefinitionReader.setResourceLoader(this.applicationContext);
        beanDefinitionReader.setEntityResolver(new ResourceEntityResolver(this.applicationContext));
        beanDefinitionReader.loadBeanDefinitions(new InMemoryResource(contextString));
        beanDefinitionReader.setBeanClassLoader(GroovyDynamicLoader.class.getClassLoader());
    }

    /**
     * 对groovy脚本做语法检查
     *
     * @param template
     * @return
     */
    private boolean checkSyntax(GroovyBeanTemplate template) {

        String className = template.getClassName();
        if (StringUtils.isEmpty(template.getTemplateContent())) {
            logger.error("Groovy template content is empty, class name = {}", className);
            return false;
        }

        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            groovyClassLoader.parseClass(template.getTemplateContent());
            stopWatch.stop();
            logger.info("Groovy syntax check, class = {}, takes {} ms", className, stopWatch.getLastTaskTimeMillis());
        } catch (Exception e) {
            logger.error("Groovy syntax check error, class = " + className, e);
            return false;
        }

        return true;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = (ConfigurableApplicationContext) applicationContext;
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        //确定加载自定义类QuickRefreshFactoryPostProcessor
        applicationContext.getBeanFactory().addBeanPostProcessor(
                (BeanPostProcessor) applicationContext.getBean(QUICK_REFRESH_FACTORY_POST_PROCESSOR));

        //预加载
        applicationContext.getBeanFactory().getBeanNamesForType(BeanPostProcessor.class, true, false);
    }
}
