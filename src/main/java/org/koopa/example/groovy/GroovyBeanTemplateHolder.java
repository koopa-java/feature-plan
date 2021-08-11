package org.koopa.example.groovy;

import java.util.concurrent.ConcurrentHashMap;

public class GroovyBeanTemplateHolder {

    private static final ConcurrentHashMap<String, GroovyBeanTemplate> TEMPLATE_MAP = new ConcurrentHashMap<>();

    public static void put(GroovyBeanTemplate template) {
        TEMPLATE_MAP.put(template.getClassName().toLowerCase(), template);
    }

    public static GroovyBeanTemplate get(String key) {
        return TEMPLATE_MAP.get(key.toLowerCase());
    }

    public static boolean containsKey(String className) {
        return TEMPLATE_MAP.containsKey(className.toLowerCase());
    }
}
