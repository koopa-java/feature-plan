package org.koopa.example.groovy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.koopa.example.groovy.impl.IGroovyScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

@RunWith(SpringRunner.class)
@TestExecutionListeners({DependencyInjectionTestExecutionListener.class})
@ContextConfiguration("classpath:applicationContext.xml")
public class GroovyTest {
	@Autowired
	private GroovyBeanManager groovyBeanManager;

	@Test
	public void test(){
		/**
		 * script内容就是该类内容
		 * {@link GroovyScript}
		 */
		String script =   "package org.koopa.example.groovy\n"
						+ "\n"
						+ "import org.koopa.example.groovy.impl.IGroovyScript\n"
						+ "\n"
						+ "class GroovyScript implements IGroovyScript {\n"
						+ "    @Override\n"
						+ "    void execute() {\n"
						+ "        println \"execute success!\"\n"
						+ "    }\n"
						+ "}\n";
		GroovyBeanTemplate template = new GroovyBeanTemplate();
		template.setClassName("scriptTest");
		template.setTemplateContent(script);

		IGroovyScript groovyScript = groovyBeanManager.getBean(template, IGroovyScript.class);
		groovyScript.execute();
	}
}
