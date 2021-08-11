package org.koopa.example.groovy;

import org.springframework.core.io.AbstractResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 内存资源，主要用来将编译后的解析脚本存于内存
 */
public class InMemoryResource extends AbstractResource {

    /**
     * 资源描述
     */
    private static final String DESCRIPTION = "InMemoryResource";

    /**
     * 脚本来源
     */
    private final byte[] source;

    /**
     * @param sourceString
     */
    public InMemoryResource(String sourceString) {
        this.source = sourceString.getBytes();
    }

    /**
     * @see org.springframework.core.io.Resource#getDescription()
     */
    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * @see org.springframework.core.io.InputStreamSource#getInputStream()
     */
    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(source);
    }

    /**
     * @see AbstractResource#hashCode()
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(source);
    }

    /**
     * @see AbstractResource#equals(Object)
     */
    @Override
    public boolean equals(Object res) {
        if (!(res instanceof InMemoryResource)) {
            return false;
        }

        return Arrays.equals(source, ((InMemoryResource) res).source);
    }

}
