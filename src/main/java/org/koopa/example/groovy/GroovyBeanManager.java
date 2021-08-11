package org.koopa.example.groovy;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GroovyBeanManager implements ApplicationContextAware {


    private ApplicationContext applicationContext;

    private void addTemplates(List<GroovyBeanTemplate> templates) {
        templates.forEach(GroovyBeanTemplateHolder::put);
    }

    public static GroovyBeanTemplate getTemplate(String scriptName) {
        return GroovyBeanTemplateHolder.get(scriptName);
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(GroovyBeanTemplate template, Class<T> tClass) {

        String className = template.getClassName();
        //bean 存在， 且一致，不需修改， 返回bean
        if (template.equals(GroovyBeanTemplateHolder.get(className))) {
            return (T) applicationContext.getBean(className);
        }

        synchronized (this) {
            // double check
            if (template.equals(GroovyBeanTemplateHolder.get(className))) {
                return (T) applicationContext.getBean(className);
            }

            GroovyDynamicLoader groovyDynamicLoader = (GroovyDynamicLoader) applicationContext
                    .getBean("groovyDynamicLoader");

            // bean存在，但不一致，需删除
            if (GroovyBeanTemplateHolder.containsKey(className)) {
                groovyDynamicLoader.removeBeanTemplate(template);
            }

            groovyDynamicLoader.addBeanTemplate(template);
            GroovyBeanTemplateHolder.put(template);
            return (T) applicationContext.getBean(className);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
