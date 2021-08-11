package org.koopa.example.groovy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scripting.ScriptSource;

/**
 * Groovy脚本数据库数据源，实现了spring的ScriptSource接口供spring来管理Groovy脚本<br>
 * 每次获取解析报文的Groovy脚本时，从已有的缓存 ParserCache 中读取。
 */
public final class DatabaseScriptSource implements ScriptSource {

    /**
     * 脚本名称
     */
    private final String scriptName;

    /**
     * 构造函数
     *
     * @param scriptName
     */
    public DatabaseScriptSource(String scriptName) {
        this.scriptName = scriptName;
    }

    /**
     * @see ScriptSource#getScriptAsString()
     */
    @Override
    public String getScriptAsString() {

        GroovyBeanTemplate template = GroovyBeanTemplateHolder.get(scriptName);

        // 脚本模板配置为空，可能已经被删除，尝试在最后一次删除的模板中查找
        if (template == null) {
            return StringUtils.EMPTY;
        }

        return template.getTemplateContent();
    }

    /**
     * @see ScriptSource#isModified()
     */
    @Override
    public boolean isModified() {
        return false;
    }

    /**
     * @see ScriptSource#suggestedClassName()
     */
    @Override
    public String suggestedClassName() {
        return org.springframework.util.StringUtils.stripFilenameExtension(this.scriptName).toLowerCase();
    }

}
