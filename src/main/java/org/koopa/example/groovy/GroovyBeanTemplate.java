package org.koopa.example.groovy;

public class GroovyBeanTemplate {


    private String templateContent;

    private String className;


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GroovyBeanTemplate that = (GroovyBeanTemplate) o;
        if (!templateContent.equals(that.templateContent)) {
            return false;
        }
        return className.equals(that.className);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + templateContent.hashCode();
        result = 31 * result + className.hashCode();
        return result;
    }


    /**
     * 获取 templateContent
     *
     * @return templateContent
     */
    public String getTemplateContent() {
        return templateContent;
    }

    /**
     * 设置 templateContent
     *
     * @param templateContent templateContent
     */
    public void setTemplateContent(String templateContent) {
        this.templateContent = templateContent;
    }

    /**
     * 获取 className
     *
     * @return className
     */
    public String getClassName() {
        return className;
    }

    /**
     * 设置 className
     *
     * @param className className
     */
    public void setClassName(String className) {
        this.className = className;
    }


}
